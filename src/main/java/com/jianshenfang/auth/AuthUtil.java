package com.jianshenfang.auth;

import com.jianshenfang.common.BusinessException;

/**
 * 角色校验辅助。Controller 层只做参数接收与角色判断，业务在 Service。
 */
public final class AuthUtil {
    private AuthUtil() {}

    public static void requireAdmin() {
        if (!"ADMIN".equals(AuthContext.role())) {
            throw new BusinessException("无权限：需要管理员身份");
        }
    }

    public static void requireStaff() {
        String role = AuthContext.role();
        if (!"ADMIN".equals(role) && !"COACH".equals(role)) {
            throw new BusinessException("无权限：需要管理员或教练身份");
        }
    }
}
