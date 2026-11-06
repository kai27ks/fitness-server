package com.jianshenfang.controller;

import com.jianshenfang.common.Result;
import com.jianshenfang.dto.WechatDtos;
import com.jianshenfang.service.WechatAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信票据交换与认领。/api/auth/** 已被 WebConfig 放行，无需登录态。
 * 未绑定用户只持有一次性 claimTicket，不会拿到业务 JWT。
 */
@RestController
@RequestMapping("/api/auth/wechat")
@RequiredArgsConstructor
public class WechatAuthController {

    private final WechatAuthService wechatAuthService;

    @PostMapping("/ticket")
    public Result<WechatDtos.TicketResponse> ticket(@Valid @RequestBody WechatDtos.TicketRequest req) {
        return Result.ok(wechatAuthService.exchangeTicket(req.ticket()));
    }

    @PostMapping("/claim")
    public Result<WechatDtos.ClaimResponse> claim(@Valid @RequestBody WechatDtos.ClaimRequest req) {
        return Result.ok(wechatAuthService.claim(req));
    }
}
