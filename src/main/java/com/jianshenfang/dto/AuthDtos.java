package com.jianshenfang.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}

    public record SmsCodeRequest(@NotBlank(message = "手机号不能为空") String phone) {}

    public record RegisterRequest(
            @NotBlank(message = "手机号不能为空") String phone,
            @NotBlank(message = "验证码不能为空") String code,
            @NotBlank(message = "密码不能为空") @Size(min = 6, max = 20, message = "密码长度需为 6-20 位") String password,
            String name) {}

    public record LoginPasswordRequest(
            @NotBlank(message = "手机号不能为空") String phone,
            @NotBlank(message = "密码不能为空") String password) {}

    public record LoginCodeRequest(
            @NotBlank(message = "手机号不能为空") String phone,
            @NotBlank(message = "验证码不能为空") String code) {}

    public record ResetPasswordRequest(
            @NotBlank(message = "手机号不能为空") String phone,
            @NotBlank(message = "验证码不能为空") String code,
            @NotBlank(message = "新密码不能为空") @Size(min = 6, max = 20, message = "密码长度需为 6-20 位") String password) {}

    /** mock 模式下直接回传验证码，模拟短信 */
    public record SmsCodeResponse(String phone, String mockCode) {}

    public record LoginResponse(String token, String role, String name, Long memberId) {}
}
