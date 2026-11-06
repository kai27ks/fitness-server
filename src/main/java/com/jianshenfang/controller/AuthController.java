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

/** 短信相关接口已下线（Q2=A），保留账密登录供管理员/教练及已建档会员使用 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login/password")
    public Result<AuthDtos.LoginResponse> loginPassword(@Valid @RequestBody AuthDtos.LoginPasswordRequest req) {
        return Result.ok(authService.loginByPassword(req));
    }
}
