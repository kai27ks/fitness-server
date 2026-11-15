package com.jianshenfang.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.jianshenfang.auth.AuthUtil;
import com.jianshenfang.common.Result;
import com.jianshenfang.dto.WechatDtos;
import com.jianshenfang.service.WechatAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 管理端微信相关操作：Q13 允许教练建档，故这批接口用 requireStaff() */
@RestController
@RequestMapping("/api/admin/wechat")
@RequiredArgsConstructor
public class WechatAdminController {

    private final WechatAdminService wechatAdminService;

    @GetMapping("/claim-requests")
    public Result<List<WechatDtos.ClaimRequestVO>> requests(@RequestParam(required = false) String status) {
        AuthUtil.requireStaff();
        return Result.ok(wechatAdminService.requests(status));
    }

    @PostMapping("/claim-requests/{id}/bind")
    public Result<Void> bind(@PathVariable Long id, @Valid @RequestBody WechatDtos.HandleClaimRequest req) {
        AuthUtil.requireStaff();
        wechatAdminService.bindRequest(id, req.memberId(), req.note());
        return Result.ok();
    }

    @PostMapping("/claim-requests/{id}/reject")
    public Result<Void> reject(@PathVariable Long id, @RequestBody(required = false) WechatDtos.RejectClaimRequest req) {
        AuthUtil.requireStaff();
        wechatAdminService.rejectRequest(id, req == null ? null : req.note());
        return Result.ok();
    }

    @GetMapping("/members/{memberId}/claim-code")
    public Result<WechatDtos.ClaimCodeVO> claimCode(@PathVariable Long memberId) {
        AuthUtil.requireStaff();
        return Result.ok(wechatAdminService.claimCodeOf(memberId));
    }

    @PostMapping("/members/{memberId}/claim-code")
    public Result<WechatDtos.ClaimCodeVO> resetClaimCode(@PathVariable Long memberId) {
        AuthUtil.requireStaff();
        return Result.ok(wechatAdminService.resetClaimCode(memberId));
    }

    @GetMapping("/members/{memberId}/bind")
    public Result<WechatDtos.WechatBindVO> bindInfo(@PathVariable Long memberId) {
        AuthUtil.requireStaff();
        return Result.ok(wechatAdminService.bindInfo(memberId));
    }

    @DeleteMapping("/members/{memberId}/bind")
    public Result<Void> unbind(@PathVariable Long memberId) {
        AuthUtil.requireStaff();
        wechatAdminService.unbind(memberId);
        return Result.ok();
    }

    /** 公众号全局 access_token（应用配置凭据），供调试/联调使用 */
    @GetMapping("/access-token")
    public Result<WechatDtos.AccessTokenVO> accessToken() {
        AuthUtil.requireAdmin();
        return Result.ok(wechatAdminService.accessToken());
    }

    /** 用传入的 appid/secret 换 token（不写缓存）；不传 appid 则用配置凭据 */
    @PostMapping("/access-token")
    public Result<WechatDtos.AccessTokenVO> accessTokenByCredential(
            @RequestBody(required = false) WechatDtos.AccessTokenRequest req) {
        AuthUtil.requireAdmin();
        return Result.ok(wechatAdminService.accessToken(req == null ? null : req.appid(),
                req == null ? null : req.secret()));
    }

    /** 查看公众号当前自定义菜单 */
    @GetMapping("/menu")
    public Result<JsonNode> menu() {
        AuthUtil.requireAdmin();
        return Result.ok(wechatAdminService.getMenu());
    }

    /** 创建/覆盖自定义菜单；body 省略则创建默认「会员登录」菜单 */
    @PostMapping("/menu")
    public Result<String> createMenu(@RequestBody(required = false) JsonNode menu) {
        AuthUtil.requireAdmin();
        return Result.ok(wechatAdminService.createMenu(menu));
    }
}
