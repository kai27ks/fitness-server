package com.jianshenfang.dto;

import jakarta.validation.constraints.NotBlank;

public final class AuthDtos {
    private AuthDtos() {}

    public record LoginPasswordRequest(
            @NotBlank(message = "手机号不能为空") String phone,
            @NotBlank(message = "密码不能为空") String password) {}

    public record LoginResponse(String token, String role, String name, Long memberId) {}
}
