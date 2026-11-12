package com.jianshenfang.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CoachAccountDtos {
    private CoachAccountDtos() {}

    /** state: UNBOUND 未开通；MISSING 关联账号已不存在；BOUND 已关联。绝不返回密码。 */
    public record Account(Long coachId, Long userId, String phone, String role, Integer status, String state) {}

    public record Configure(
            @NotBlank(message = "请选择创建或关联账号")
            @Pattern(regexp = "CREATE|BIND", message = "请选择创建或关联账号") String mode,
            @NotBlank(message = "请填写登录手机号")
            @Pattern(regexp = "1\\d{10}", message = "请输入正确的11位手机号") String phone,
            String initialPassword) {}

    public record ResetPassword(
            @NotBlank(message = "请设置新密码")
            @Size(min = 8, max = 64, message = "新密码需为8～64位") String newPassword) {}
}
