package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.config.WxProperties;
import com.jianshenfang.dto.AuthDtos;
import com.jianshenfang.dto.WechatDtos;
import com.jianshenfang.entity.*;
import com.jianshenfang.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公众号网页授权 + 手机号/认领码认领。
 *
 * 设计要点（见 docs/wechat-oa-design.md §17）：
 * 1. 不新建「无手机号账号」——sys_user.phone / member.phone 均 NOT NULL + UNIQUE；
 * 2. 首次进入只发一次性 CLAIM_TICKET，不签发业务 JWT，也不落正式账号；
 * 3. 认领命中教练已建档的会员后，把 openid 绑定到该 sys_user.id，
 *    复用 AuthService.loginSuccess 签发其 JWT —— 课包/预约/风采数据零迁移；
 * 4. 手机号未命中绝不自动建档，进「待核实认领申请」队列。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatAuthService {

    private static final String STATE = "STATE";
    private static final String CLAIM_TICKET = "CLAIM_TICKET";

    private static final String ROLE_MEMBER = "MEMBER";
    private static final String ROLE_COACH = "COACH";
    private static final String ROLE_ADMIN = "ADMIN";
    /** 教练侧用静默授权：点菜单直接进，不弹「是否允许」 */
    private static final String SCOPE_BASE = "snsapi_base";

    /** 教练绑定密码失败限制：同 openid + 手机号 5 次锁 15 分钟（进程内，重启清零） */
    private static final int COACH_CLAIM_MAX_FAIL = 5;
    private static final long COACH_CLAIM_LOCK_SECONDS = 15 * 60;

    private final WxProperties wx;
    private final ObjectMapper objectMapper;
    private final WechatAccountMapper wechatAccountMapper;
    private final MemberClaimCodeMapper claimCodeMapper;
    private final WechatTempTokenMapper tempTokenMapper;
    private final WechatClaimRequestMapper claimRequestMapper;
    private final MemberMapper memberMapper;
    private final SysUserMapper userMapper;
    private final CoachMapper coachMapper;
    private final AuthService authService;
    private final WechatBindService bindService;
    private final WxApiClient wxApiClient;
    private final OperationLogService logService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** 教练绑定失败计数：key = openid|phone */
    private final Map<String, FailCounter> coachClaimFails = new ConcurrentHashMap<>();

    // ==================== 网页授权 ====================

    /** 真实模式：返回微信授权地址，由 Controller 302 */
    public String buildAuthorizeUrl(String redirect, String role) {
        String r = normalizeRole(role);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("redirect", safeRedirect(redirect));
        payload.put("role", r);
        String state = issue(STATE, payload.toString(), wx.getStateTtlSeconds());
        String cb = enc(wx.getCallbackBaseUrl() + "/wechat/oauth/callback");
        return "https://open.weixin.qq.com/connect/oauth2/authorize"
                + "?appid=" + enc(wx.getAppid())
                + "&redirect_uri=" + cb
                + "&response_type=code"
                + "&scope=" + enc(ROLE_COACH.equals(r) ? SCOPE_BASE : wx.getScope())
                + "&state=" + enc(state)
                + "#wechat_redirect";
    }

    /** dev 模式：跳过微信服务器，直接用一个模拟 openid 完成授权 */
    public WechatDtos.Identity mockIdentity(String rawOpenid) {
        String suffix = (rawOpenid == null || rawOpenid.isBlank())
                ? UUID.randomUUID().toString().substring(0, 8) : rawOpenid.trim();
        String openid = "MOCK_" + suffix;
        if (openid.length() > 64) openid = openid.substring(0, 64);
        return new WechatDtos.Identity("mock-appid", openid, null, null, "微信用户");
    }

    /** 微信回调：校验 state → code 换 openid → 生成一次性票据 → 返回前端地址 */
    public String handleCallback(String code, String state) {
        JsonNode statePayload = consume(STATE, state);
        String redirect = statePayload.path("redirect").asText("/");
        String role = normalizeRole(statePayload.path("role").asText(null));
        WechatDtos.Identity id = exchangeOpenid(code);
        return finish(id, redirect, role);
    }

    /** 授权完成（真实回调与 dev mock 共用）：已绑定直接带上 userId，未绑定走认领 */
    public String finish(WechatDtos.Identity id, String redirect, String role) {
        String intent = normalizeRole(role);
        WechatAccount bound = wechatAccountMapper.selectOne(new LambdaQueryWrapper<WechatAccount>()
                .eq(WechatAccount::getAppid, id.appid())
                .eq(WechatAccount::getOpenid, id.openid()));
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("appid", id.appid());
        payload.put("openid", id.openid());
        payload.put("unionid", id.unionid());
        payload.put("avatarUrl", id.avatarUrl());
        payload.put("nickname", id.nickname());
        payload.put("redirect", safeRedirect(redirect));
        payload.put("intent", intent);
        if (bound != null) payload.put("userId", bound.getUserId());
        payload.put("subscribed", refreshSubscribe(id.openid(), bound));
        String ticket = issue(CLAIM_TICKET, payload.toString(), wx.getTicketTtlSeconds());
        return wx.getH5BaseUrl() + "/wechat/entry?ticket=" + enc(ticket);
    }

    /** 刷新关注状态：真实模式调 cgi-bin/user/info；关注/取关事件推送会实时更新此字段 */
    private boolean refreshSubscribe(String openid, WechatAccount bound) {
        if (wx.isMock()) return !wx.isMockFollowRequired();
        Boolean subscribed = wxApiClient.subscribed(openid);
        if (subscribed != null && bound != null) {
            WechatAccount upd = new WechatAccount();
            upd.setId(bound.getId());
            upd.setSubscribed(subscribed ? 1 : 0);
            if (subscribed) upd.setSubscribedAt(LocalDateTime.now());
            else upd.setUnsubscribedAt(LocalDateTime.now());
            wechatAccountMapper.updateById(upd);
        }
        if (subscribed != null) return subscribed;
        return bound != null && bound.getSubscribed() != null && bound.getSubscribed() == 1;
    }

    public String errorUrl(String message) {
        return wx.getH5BaseUrl() + "/wechat/entry?error=" + enc(message);
    }

    /** 带入口角色的错误页：重试时仍按进入时的身份（教练/管理员 vs 会员）重新授权 */
    public String errorUrl(String message, String role) {
        String url = errorUrl(message);
        return ROLE_COACH.equalsIgnoreCase(role == null ? "" : role.trim()) ? url + "&role=COACH" : url;
    }

    // ==================== 票据换登录态 ====================

    public WechatDtos.TicketResponse exchangeTicket(String ticket) {
        JsonNode p = consume(CLAIM_TICKET, ticket);
        long userId = p.path("userId").asLong(0L);
        String redirect = p.path("redirect").asText("/");
        String intent = normalizeRole(p.path("intent").asText(null));
        if (userId > 0) {
            SysUser user = userMapper.selectById(userId);
            if (user == null || user.getStatus() == null || user.getStatus() != 1) {
                throw new BusinessException("账号不可用，请联系前台");
            }
            // 教练离职/停用后立即失效，不依赖前端拦截
            if (ROLE_COACH.equals(user.getRole())) {
                Coach coach = coachMapper.selectOne(new LambdaQueryWrapper<Coach>()
                        .eq(Coach::getUserId, userId));
                if (coach == null || coach.getStatus() == null || coach.getStatus() != 1) {
                    throw new BusinessException("教练账号已停用，请联系店长");
                }
            }
            AuthDtos.LoginResponse login = authService.loginSuccess(user);
            return new WechatDtos.TicketResponse(login.token(), login.role(), login.name(),
                    login.memberId(), false, null, redirect, null, null,
                    needFollow(p), wx.getQrcodeUrl(), null);
        }
        // 未绑定：换发一个有效期更长的认领票据，供绑定页提交
        String claimTicket = issue(CLAIM_TICKET, p.toString(), wx.getClaimTicketTtlSeconds());
        return new WechatDtos.TicketResponse(null, null, null, null,
                true, claimTicket, redirect, text(p, "avatarUrl"), text(p, "nickname"),
                needFollow(p), wx.getQrcodeUrl(), intent);
    }

    private boolean needFollow(JsonNode p) {
        return wx.isNotifyEnabled() && !p.path("subscribed").asBoolean(true);
    }

    // ==================== 认领 ====================

    public WechatDtos.ClaimResponse claim(WechatDtos.ClaimRequest req) {
        JsonNode p = readTicket(CLAIM_TICKET, req.claimTicket());
        WechatDtos.Identity id = new WechatDtos.Identity(
                text(p, "appid"), text(p, "openid"), text(p, "unionid"),
                text(p, "avatarUrl"), text(p, "nickname"));
        if (id.openid() == null || id.openid().isBlank()) {
            throw new BusinessException("认领凭证异常，请重新从公众号进入");
        }
        String phone = req.phone().trim();
        if (!phone.matches("^1\\d{10}$")) {
            throw new BusinessException("请输入正确的手机号");
        }

        Member member = memberMapper.selectOne(new LambdaQueryWrapper<Member>()
                .eq(Member::getPhone, phone));
        if (member == null) {
            // Q14：不自动建档，进待核实队列
            createClaimRequest(id, phone);
            throw new BusinessException("未找到该手机号对应的会员档案，已提交前台核实，请联系教练");
        }

        MemberClaimCode code = claimCodeMapper.selectOne(new LambdaQueryWrapper<MemberClaimCode>()
                .eq(MemberClaimCode::getMemberId, member.getId()));
        if (code == null || code.getUsedAt() != null || code.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException("认领码无效或已过期，请联系教练重新生成");
        }
        int fails = code.getFailCount() == null ? 0 : code.getFailCount();
        if (fails >= wx.getClaimCodeMaxFail()) {
            throw new BusinessException("认领码错误次数过多，请联系教练重置");
        }
        if (!code.getCode().equals(req.claimCode().trim())) {
            MemberClaimCode upd = new MemberClaimCode();
            upd.setId(code.getId());
            upd.setFailCount(fails + 1);
            claimCodeMapper.updateById(upd);
            throw new BusinessException("认领码不正确，请核对小票或联系教练");
        }

        SysUser user = userMapper.selectById(member.getUserId());
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException("账号不可用，请联系前台");
        }
        // 输入校验失败时保留票据，允许用户在有效期内重试；成功前原子核销防重放。
        consume(CLAIM_TICKET, req.claimTicket());
        bindService.bind(id, member.getUserId(), member.getId(), code);

        WechatAccount bound = wechatAccountMapper.selectOne(new LambdaQueryWrapper<WechatAccount>()
                .eq(WechatAccount::getOpenid, id.openid()));
        boolean subscribed = refreshSubscribe(id.openid(), bound);
        AuthDtos.LoginResponse login = authService.loginSuccess(user);
        return new WechatDtos.ClaimResponse(login.token(), login.role(), login.name(),
                login.memberId(), wx.isNotifyEnabled() && !subscribed, wx.getQrcodeUrl());
    }

    // ==================== 教练绑定 ====================

    /**
     * 管理端首次绑定：必须用「手机号 + 密码」，不能用会员认领码。
     * COACH 与 ADMIN 都允许从「教练工作台」入口绑定；管理员可以没有教练档案。
     * 校验顺序：票据 intent → 账号角色 → 密码 → 教练在职（有档案时）→ 绑定。
     */
    public WechatDtos.ClaimResponse coachClaim(WechatDtos.CoachClaimRequest req) {
        JsonNode p = readTicket(CLAIM_TICKET, req.claimTicket());
        if (!ROLE_COACH.equals(normalizeRole(p.path("intent").asText(null)))) {
            throw new BusinessException("该凭证不是教练入口签发的，请从公众号「教练工作台」重新进入");
        }
        WechatDtos.Identity id = new WechatDtos.Identity(
                text(p, "appid"), text(p, "openid"), text(p, "unionid"),
                text(p, "avatarUrl"), text(p, "nickname"));
        if (id.openid() == null || id.openid().isBlank()) {
            throw new BusinessException("绑定凭证异常，请重新从公众号进入");
        }
        String phone = req.phone() == null ? "" : req.phone().trim();
        if (!phone.matches("^1\\d{10}$")) throw new BusinessException("请输入正确的手机号");

        String failKey = id.openid() + "|" + phone;
        assertNotLocked(failKey);

        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, phone));
        if (user == null) throw new BusinessException("账号不存在，请联系店长开通账号");
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException("账号已停用，请联系店长");
        }
        String role = user.getRole();
        if (!ROLE_COACH.equals(role) && !ROLE_ADMIN.equals(role)) {
            throw new BusinessException("该账号不是教练或管理员账号，请从公众号「会员中心」进入");
        }
        if (!encoder.matches(req.password(), user.getPassword())) {
            recordFail(failKey);
            throw new BusinessException("手机号或密码错误");
        }
        // 教练必须有一份在职档案；管理员可以没有教练档案，绑定后仍以 ADMIN 身份进工作台
        Coach coach = coachMapper.selectOne(new LambdaQueryWrapper<Coach>().eq(Coach::getUserId, user.getId()));
        if (ROLE_COACH.equals(role) && coach == null) throw new BusinessException("未找到教练档案，请联系店长");
        // 只有教练本人受在职状态约束；管理员即使关联了已停用的教练档案，仍可用管理员身份绑定
        if (ROLE_COACH.equals(role) && coach != null && (coach.getStatus() == null || coach.getStatus() != 1)) {
            throw new BusinessException("教练账号已停用，请联系店长");
        }

        // 校验全部通过才核销票据，失败时允许在有效期内重试
        consume(CLAIM_TICKET, req.claimTicket());
        Long coachId = coach == null ? null : coach.getId();
        bindService.bindCoach(id, user.getId(), coachId);
        coachClaimFails.remove(failKey);
        logService.log("CLAIM_COACH", coach == null ? "ADMIN" : "COACH", coachId,
                (coach == null ? "管理员" : "教练") + "微信绑定 openid=" + tail(id.openid()));

        WechatAccount bound = wechatAccountMapper.selectOne(new LambdaQueryWrapper<WechatAccount>()
                .eq(WechatAccount::getOpenid, id.openid()));
        boolean subscribed = refreshSubscribe(id.openid(), bound);
        AuthDtos.LoginResponse login = authService.loginSuccess(user);
        return new WechatDtos.ClaimResponse(login.token(), login.role(), coach == null ? "管理员" : coach.getName(), null,
                wx.isNotifyEnabled() && !subscribed, wx.getQrcodeUrl());
    }

    private void assertNotLocked(String key) {
        FailCounter c = coachClaimFails.get(key);
        if (c == null || c.until() == null) return;
        if (LocalDateTime.now().isBefore(c.until())) {
            throw new BusinessException("密码错误次数过多，请 15 分钟后再试");
        }
        coachClaimFails.remove(key);
    }

    private void recordFail(String key) {
        coachClaimFails.compute(key, (k, v) -> {
            int n = (v == null ? 0 : v.count()) + 1;
            LocalDateTime until = n >= COACH_CLAIM_MAX_FAIL
                    ? LocalDateTime.now().plusSeconds(COACH_CLAIM_LOCK_SECONDS) : null;
            return new FailCounter(n, until);
        });
    }

    private String normalizeRole(String role) {
        String r = role == null ? "" : role.trim();
        return ROLE_COACH.equalsIgnoreCase(r) ? ROLE_COACH : ROLE_MEMBER;
    }

    private String tail(String openid) {
        if (openid == null || openid.length() <= 6) return "***";
        return "***" + openid.substring(openid.length() - 6);
    }

    private record FailCounter(int count, LocalDateTime until) {}

    private void createClaimRequest(WechatDtos.Identity id, String phone) {
        try {
            WechatClaimRequest r = new WechatClaimRequest();
            r.setAppid(id.appid());
            r.setOpenid(id.openid());
            r.setUnionid(id.unionid());
            r.setAvatarUrl(id.avatarUrl());
            r.setNickname(id.nickname());
            r.setPhoneInput(phone);
            r.setStatus("PENDING");
            r.setCreateTime(LocalDateTime.now());
            claimRequestMapper.insert(r);
            logService.log("CLAIM_REQUEST_PENDING", "WECHAT", null,
                    "手机号未命中，待前台核实 phone=" + mask(phone));
        } catch (Exception e) {
            log.warn("写入待核实认领申请失败", e);
        }
    }

    // ==================== 微信接口 ====================

    private WechatDtos.Identity exchangeOpenid(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException("微信授权码缺失，请重新进入");
        }
        String url = "https://api.weixin.qq.com/sns/oauth2/access_token"
                + "?appid=" + enc(wx.getAppid())
                + "&secret=" + enc(wx.getSecret())
                + "&code=" + enc(code)
                + "&grant_type=authorization_code";
        JsonNode token = getJson(url);
        if (token == null || token.has("errcode") || !token.has("openid")) {
            log.error("微信 code 换取 openid 失败: {}", token);
            throw new BusinessException("微信授权失败，请重新从公众号进入");
        }
        String openid = token.path("openid").asText();
        String unionid = text(token, "unionid");
        String accessToken = token.path("access_token").asText();
        String scope = token.path("scope").asText();

        String nickname = null;
        String avatar = null;
        if (scope.contains("userinfo")) {
            String infoUrl = "https://api.weixin.qq.com/sns/userinfo"
                    + "?access_token=" + enc(accessToken)
                    + "&openid=" + enc(openid) + "&lang=zh_CN";
            JsonNode info = getJson(infoUrl);
            // 用户拒绝授权时仍会用 code 换到 openid，此处只是拿不到资料，不阻断流程
            if (info != null && !info.has("errcode")) {
                nickname = text(info, "nickname");
                avatar = storeAvatar(text(info, "headimgurl"));
            }
        }
        return new WechatDtos.Identity(wx.getAppid(), openid, unionid, avatar, nickname);
    }

    private JsonNode getJson(String url) {
        try {
            String body = restTemplate.getForObject(url, String.class);
            return body == null ? null : objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("调用微信接口失败: {}", e.getMessage());
            return null;
        }
    }

    /** 头像转存到本站，避免 wx.qlogo.cn 外链防盗链/失效；失败则降级为无头像，不阻断注册 */
    private String storeAvatar(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI uri = URI.create(raw);
            String host = uri.getHost();
            if (host == null || !(host.endsWith("qlogo.cn") || host.endsWith("weixin.qq.com"))) {
                return null;
            }
            byte[] bytes = restTemplate.getForObject(raw, byte[].class);
            if (bytes == null || bytes.length == 0 || bytes.length > 2_000_000) return null;
            Path dir = Paths.get(wx.getAvatarUploadDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String name = UUID.randomUUID().toString().replace("-", "") + ".jpg";
            Files.write(dir.resolve(name), bytes);
            return wx.getAvatarUrlPrefix() + "/" + name;
        } catch (Exception e) {
            log.warn("微信头像转存失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 一次性票据 ====================

    private String issue(String type, String payload, int ttlSeconds) {
        WechatTempToken t = new WechatTempToken();
        t.setToken(UUID.randomUUID().toString().replace("-", ""));
        t.setType(type);
        t.setPayload(payload);
        t.setCreateTime(LocalDateTime.now());
        t.setExpireAt(LocalDateTime.now().plusSeconds(ttlSeconds));
        tempTokenMapper.insert(t);
        return t.getToken();
    }

    /** 校验并立即核销，防重放 */
    private JsonNode consume(String type, String token) {
        JsonNode payload = readTicket(type, token);
        int updated = tempTokenMapper.update(null, new LambdaUpdateWrapper<WechatTempToken>()
                .eq(WechatTempToken::getToken, token)
                .eq(WechatTempToken::getType, type)
                .isNull(WechatTempToken::getUsedAt)
                .gt(WechatTempToken::getExpireAt, LocalDateTime.now())
                .set(WechatTempToken::getUsedAt, LocalDateTime.now()));
        if (updated != 1) {
            throw new BusinessException("凭证已过期或已使用，请重新从公众号进入");
        }
        return payload;
    }

    private JsonNode readTicket(String type, String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException("凭证缺失，请重新从公众号进入");
        }
        WechatTempToken t = tempTokenMapper.selectById(token);
        if (t == null || !type.equals(t.getType()) || t.getUsedAt() != null
                || t.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException("凭证已过期或已使用，请重新从公众号进入");
        }
        try {
            return objectMapper.readTree(t.getPayload());
        } catch (Exception e) {
            throw new BusinessException("凭证数据异常，请重新进入");
        }
    }

    // ==================== 工具 ====================

    private String safeRedirect(String redirect) {
        if (redirect == null || !redirect.startsWith("/") || redirect.startsWith("//")
                || redirect.contains("://") || redirect.contains("\n")) {
            return "/";
        }
        return redirect;
    }

    private String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private String mask(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
