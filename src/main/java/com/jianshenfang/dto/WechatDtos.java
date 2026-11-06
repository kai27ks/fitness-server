package com.jianshenfang.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public final class WechatDtos {
    private WechatDtos() {}

    /** 微信侧识别到的身份（openid 只由后端用 code 换取，前端永不上传） */
    public record Identity(String appid, String openid, String unionid, String avatarUrl, String nickname) {}

    public record TicketRequest(@NotBlank(message = "票据不能为空") String ticket) {}

    public record ClaimRequest(
            @NotBlank(message = "认领票据不能为空") String claimTicket,
            @NotBlank(message = "手机号不能为空") String phone,
            @NotBlank(message = "认领码不能为空") String claimCode) {}

    /** needClaim=true 跳 /wechat/bind；needFollow=true 跳 /wechat/follow（可跳过） */
    public record TicketResponse(String token, String role, String name, Long memberId,
                                 boolean needClaim, String claimTicket, String redirect,
                                 String avatarUrl, String nickname,
                                 boolean needFollow, String qrcodeUrl) {}

    /** 认领成功：签发既有账号 JWT，并告知是否需要引导关注 */
    public record ClaimResponse(String token, String role, String name, Long memberId,
                                boolean needFollow, String qrcodeUrl) {}

    public record ClaimCodeVO(Long memberId, String code, LocalDateTime expireAt, boolean used) {}

    public record WechatBindVO(boolean bound, String openidTail, String nickname, String avatarUrl) {}

    public record ClaimRequestVO(Long id, String openid, String phoneInput, String nickname,
                                 String avatarUrl, String status, LocalDateTime createTime) {}

    public record HandleClaimRequest(@NotNull(message = "请选择要绑定的会员") Long memberId, String note) {}

    public record RejectClaimRequest(String note) {}

    public record NotifyLogVO(Long id, String scene, String openid, String status,
                              Integer errcode, String errmsg, String content, LocalDateTime createTime) {}
}
