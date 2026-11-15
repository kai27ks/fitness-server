package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianshenfang.auth.AuthContext;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.config.WxProperties;
import com.jianshenfang.dto.WechatDtos;
import com.jianshenfang.entity.*;
import com.jianshenfang.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** 管理端：认领码、待核实认领申请、微信绑定状态、自定义菜单 */
@Service
@RequiredArgsConstructor
public class WechatAdminService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WxProperties wx;
    private final ObjectMapper objectMapper;
    private final WxApiClient wxApiClient;
    private final MemberMapper memberMapper;
    private final MemberClaimCodeMapper claimCodeMapper;
    private final WechatClaimRequestMapper claimRequestMapper;
    private final WechatAccountMapper wechatAccountMapper;
    private final WechatBindService bindService;
    private final OperationLogService logService;

    /** 建档时自动生成，或教练在会员详情页手动重置 */
    @Transactional
    public WechatDtos.ClaimCodeVO resetClaimCode(Long memberId) {
        Member m = requireMember(memberId);
        claimCodeMapper.delete(new LambdaQueryWrapper<MemberClaimCode>()
                .eq(MemberClaimCode::getMemberId, m.getId()));
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        LocalDateTime now = LocalDateTime.now();
        MemberClaimCode cc = new MemberClaimCode();
        cc.setMemberId(m.getId());
        cc.setCode(code);
        cc.setFailCount(0);
        cc.setCreateTime(now);
        cc.setExpireAt(now.plusDays(wx.getClaimCodeExpireDays()));
        claimCodeMapper.insert(cc);
        logService.log("RESET_CLAIM_CODE", "MEMBER", m.getId(), "生成/重置会员认领码");
        return new WechatDtos.ClaimCodeVO(m.getId(), code, cc.getExpireAt(), false);
    }

    public WechatDtos.ClaimCodeVO claimCodeOf(Long memberId) {
        requireMember(memberId);
        MemberClaimCode cc = claimCodeMapper.selectOne(new LambdaQueryWrapper<MemberClaimCode>()
                .eq(MemberClaimCode::getMemberId, memberId));
        if (cc == null) return null;
        return new WechatDtos.ClaimCodeVO(memberId, cc.getCode(), cc.getExpireAt(), cc.getUsedAt() != null);
    }

    public List<WechatDtos.ClaimRequestVO> requests(String status) {
        String s = (status == null || status.isBlank()) ? "PENDING" : status;
        return claimRequestMapper.selectList(new LambdaQueryWrapper<WechatClaimRequest>()
                        .eq(WechatClaimRequest::getStatus, s)
                        .orderByDesc(WechatClaimRequest::getCreateTime))
                .stream()
                .map(r -> new WechatDtos.ClaimRequestVO(r.getId(), r.getOpenid(), r.getPhoneInput(),
                        r.getNickname(), r.getAvatarUrl(), r.getStatus(), r.getCreateTime()))
                .toList();
    }

    /** 教练核实身份后，把申请里的 openid 直接绑到指定会员（无需认领码） */
    @Transactional
    public void bindRequest(Long requestId, Long memberId, String note) {
        WechatClaimRequest r = claimRequestMapper.selectById(requestId);
        if (r == null || !"PENDING".equals(r.getStatus())) {
            throw new BusinessException("该申请不存在或已处理");
        }
        Member m = requireMember(memberId);
        WechatDtos.Identity id = new WechatDtos.Identity(r.getAppid(), r.getOpenid(), r.getUnionid(),
                r.getAvatarUrl(), r.getNickname());
        bindService.bindByStaff(id, m.getUserId(), m.getId());

        WechatClaimRequest upd = new WechatClaimRequest();
        upd.setId(r.getId());
        upd.setStatus("HANDLED");
        upd.setHandleNote(note);
        upd.setHandledBy(AuthContext.userId());
        upd.setHandledAt(LocalDateTime.now());
        claimRequestMapper.updateById(upd);
    }

    public void rejectRequest(Long requestId, String note) {
        WechatClaimRequest r = claimRequestMapper.selectById(requestId);
        if (r == null || !"PENDING".equals(r.getStatus())) {
            throw new BusinessException("该申请不存在或已处理");
        }
        WechatClaimRequest upd = new WechatClaimRequest();
        upd.setId(r.getId());
        upd.setStatus("REJECTED");
        upd.setHandleNote(note);
        upd.setHandledBy(AuthContext.userId());
        upd.setHandledAt(LocalDateTime.now());
        claimRequestMapper.updateById(upd);
        logService.log("REJECT_CLAIM_REQUEST", "WECHAT", r.getId(), "驳回认领申请");
    }

    public WechatDtos.WechatBindVO bindInfo(Long memberId) {
        Member m = requireMember(memberId);
        WechatAccount wa = wechatAccountMapper.selectOne(new LambdaQueryWrapper<WechatAccount>()
                .eq(WechatAccount::getUserId, m.getUserId()));
        if (wa == null) return new WechatDtos.WechatBindVO(false, null, null, null);
        String openid = wa.getOpenid() == null ? "" : wa.getOpenid();
        String tail = openid.length() > 6 ? "***" + openid.substring(openid.length() - 6) : "***";
        return new WechatDtos.WechatBindVO(true, tail, wa.getNickname(), wa.getAvatarUrl());
    }

    public void unbind(Long memberId) {
        Member m = requireMember(memberId);
        bindService.unbind(m.getUserId(), m.getId());
    }

    // ==================== access_token ====================

    /** 应用配置凭据换到的全局 access_token（带缓存，剩余有效期已扣 5 分钟刷新缓冲） */
    public WechatDtos.AccessTokenVO accessToken() {
        if (wx.isMock()) throw new BusinessException("当前为 mock 模式，未连接微信服务器");
        WxApiClient.TokenInfo t = wxApiClient.tokenInfo();
        if (t == null) {
            throw new BusinessException("获取 access_token 失败，请检查 wx.appid / wx.secret 是否已配置");
        }
        return new WechatDtos.AccessTokenVO(t.token(), t.expiresIn(),
                t.expireAt() == null ? "" : t.expireAt().format(FMT));
    }

    /** 用传入的 appid/secret 换 token：只取不缓存，供临时联调另一个公众号 */
    public WechatDtos.AccessTokenVO accessToken(String appid, String secret) {
        if (appid == null || appid.isBlank()) return accessToken();
        if (secret == null || secret.isBlank()) throw new BusinessException("secret 不能为空");
        JsonNode r = wxApiClient.fetchToken(appid.trim(), secret.trim());
        if (r == null) throw new BusinessException("调用微信接口失败，请稍后重试");
        if (r.has("errcode")) {
            throw new BusinessException("获取失败：" + r.path("errcode").asInt() + " " + r.path("errmsg").asText());
        }
        int expires = r.path("expires_in").asInt(7200);
        return new WechatDtos.AccessTokenVO(r.path("access_token").asText(), expires,
                LocalDateTime.now().plusSeconds(expires).format(FMT));
    }

    // ==================== 自定义菜单 ====================

    /**
     * 创建/覆盖公众号自定义菜单。测试号后台没有菜单编辑器，只能调 cgi-bin/menu/create。
     *
     * <p>body 为空时按配置生成默认菜单（只有一个「会员登录」，点击走网页授权）；
     * 传自定义 JSON 可覆盖，格式同微信官方 menu/create 请求体。
     */
    public String createMenu(JsonNode menu) {
        JsonNode body = (menu == null || menu.isNull() || !menu.has("button")) ? defaultMenu() : menu;
        JsonNode r = wxApiClient.createMenu(body);
        if (r == null) {
            throw new BusinessException("未配置公众号 AppID/Secret，无法创建菜单");
        }
        int errcode = r.path("errcode").asInt(0);
        if (errcode != 0) {
            throw new BusinessException("创建菜单失败：" + errcode + " " + r.path("errmsg").asText());
        }
        logService.log("WECHAT_MENU_CREATE", "WECHAT", null, "创建公众号自定义菜单");
        return "菜单已创建；微信客户端有缓存，重新进入公众号或稍等几分钟可见";
    }

    public JsonNode getMenu() {
        JsonNode r = wxApiClient.getMenu();
        if (r == null) throw new BusinessException("未配置公众号 AppID/Secret，无法查询菜单");
        return r;
    }

    /** 默认菜单：按钮指向 /wechat/oauth/entry（不是 H5 页面），由后端完成网页授权后回跳 */
    private JsonNode defaultMenu() {
        String entry = trimSlash(wx.getCallbackBaseUrl()) + "/wechat/oauth/entry?redirect=";
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode buttons = root.putArray("button");
        buttons.addObject().put("type", "view").put("name", "会员登录").put("url", entry + "/");
        return root;
    }

    private static String trimSlash(String s) {
        if (s == null || s.isBlank()) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private Member requireMember(Long memberId) {
        Member m = memberMapper.selectById(memberId);
        if (m == null) throw new BusinessException("会员不存在");
        return m;
    }
}
