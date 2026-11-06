package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.UUID;

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

    private final WxProperties wx;
    private final ObjectMapper objectMapper;
    private final WechatAccountMapper wechatAccountMapper;
    private final MemberClaimCodeMapper claimCodeMapper;
    private final WechatTempTokenMapper tempTokenMapper;
    private final WechatClaimRequestMapper claimRequestMapper;
    private final MemberMapper memberMapper;
    private final SysUserMapper userMapper;
    private final AuthService authService;
    private final WechatBindService bindService;
    private final WxApiClient wxApiClient;
    private final OperationLogService logService;
    private final RestTemplate restTemplate = new RestTemplate();

    // ==================== 网页授权 ====================

    /** 真实模式：返回微信授权地址，由 Controller 302 */
    public String buildAuthorizeUrl(String redirect) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("redirect", safeRedirect(redirect));
        String state = issue(STATE, payload.toString(), wx.getStateTtlSeconds());
        String cb = enc(wx.getCallbackBaseUrl() + "/wechat/oauth/callback");
        return "https://open.weixin.qq.com/connect/oauth2/authorize"
                + "?appid=" + enc(wx.getAppid())
                + "&redirect_uri=" + cb
                + "&response_type=code"
                + "&scope=" + enc(wx.getScope())
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
        WechatDtos.Identity id = exchangeOpenid(code);
        return finish(id, redirect);
    }

    /** 授权完成（真实回调与 dev mock 共用）：已绑定直接带上 userId，未绑定走认领 */
    public String finish(WechatDtos.Identity id, String redirect) {
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

    // ==================== 票据换登录态 ====================

    public WechatDtos.TicketResponse exchangeTicket(String ticket) {
        JsonNode p = consume(CLAIM_TICKET, ticket);
        long userId = p.path("userId").asLong(0L);
        String redirect = p.path("redirect").asText("/");
        if (userId > 0) {
            SysUser user = userMapper.selectById(userId);
            if (user == null || user.getStatus() == null || user.getStatus() != 1) {
                throw new BusinessException("账号不可用，请联系前台");
            }
            AuthDtos.LoginResponse login = authService.loginSuccess(user);
            return new WechatDtos.TicketResponse(login.token(), login.role(), login.name(),
                    login.memberId(), false, null, redirect, null, null,
                    needFollow(p), wx.getQrcodeUrl());
        }
        // 未绑定：换发一个有效期更长的认领票据，供绑定页提交
        String claimTicket = issue(CLAIM_TICKET, p.toString(), wx.getClaimTicketTtlSeconds());
        return new WechatDtos.TicketResponse(null, null, null, null,
                true, claimTicket, redirect, text(p, "avatarUrl"), text(p, "nickname"),
                needFollow(p), wx.getQrcodeUrl());
    }

    private boolean needFollow(JsonNode p) {
        return wx.isNotifyEnabled() && !p.path("subscribed").asBoolean(true);
    }

    // ==================== 认领 ====================

    public WechatDtos.ClaimResponse claim(WechatDtos.ClaimRequest req) {
        JsonNode p = consume(CLAIM_TICKET, req.claimTicket());
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

        // 事务内完成绑定；冲突由唯一索引与 bindService 双重兜底
        bindService.bind(id, member.getUserId(), member.getId(), code);

        SysUser user = userMapper.selectById(member.getUserId());
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException("账号不可用，请联系前台");
        }
        WechatAccount bound = wechatAccountMapper.selectOne(new LambdaQueryWrapper<WechatAccount>()
                .eq(WechatAccount::getOpenid, id.openid()));
        boolean subscribed = refreshSubscribe(id.openid(), bound);
        AuthDtos.LoginResponse login = authService.loginSuccess(user);
        return new WechatDtos.ClaimResponse(login.token(), login.role(), login.name(),
                login.memberId(), wx.isNotifyEnabled() && !subscribed, wx.getQrcodeUrl());
    }

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
        if (token == null || token.isBlank()) {
            throw new BusinessException("凭证缺失，请重新从公众号进入");
        }
        WechatTempToken t = tempTokenMapper.selectById(token);
        if (t == null || !type.equals(t.getType()) || t.getUsedAt() != null
                || t.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException("凭证已过期或已使用，请重新从公众号进入");
        }
        WechatTempToken upd = new WechatTempToken();
        upd.setToken(token);
        upd.setUsedAt(LocalDateTime.now());
        tempTokenMapper.updateById(upd);
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
