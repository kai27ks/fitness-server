package com.jianshenfang.controller;

import com.jianshenfang.common.Result;
import com.jianshenfang.dto.AuthDtos;
import com.jianshenfang.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/sms-code")
    public Result<AuthDtos.SmsCodeResponse> smsCode(@Valid @RequestBody AuthDtos.SmsCodeRequest req) {
        String code = authService.sendCode(req.phone());
        // mock 模式回传验证码模拟短信；生产环境为 null
        return Result.ok(new AuthDtos.SmsCodeResponse(req.phone(), authService.smsMock() ? code : null));
    }

    @PostMapping("/register")
    public Result<AuthDtos.LoginResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest req) {
        return Result.ok(authService.register(req));
    }

    @PostMapping("/login/password")
    public Result<AuthDtos.LoginResponse> loginPassword(@Valid @RequestBody AuthDtos.LoginPasswordRequest req) {
        return Result.ok(authService.loginByPassword(req));
    }

    @PostMapping("/login/code")
    public Result<AuthDtos.LoginResponse> loginCode(@Valid @RequestBody AuthDtos.LoginCodeRequest req) {
        return Result.ok(authService.loginByCode(req));
    }

    @PostMapping("/password/reset")
    public Result<Void> resetPassword(@Valid @RequestBody AuthDtos.ResetPasswordRequest req) {
        authService.resetPassword(req);
        return Result.ok();
    }
}
