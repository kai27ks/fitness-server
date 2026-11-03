package com.jianshenfang.auth;

/**
 * 当前登录用户上下文（请求线程内有效，由拦截器写入/清理）
 */
public class AuthContext {

    private static final ThreadLocal<JwtUtil.AuthUser> HOLDER = new ThreadLocal<>();

    public static void set(JwtUtil.AuthUser user) {
        HOLDER.set(user);
    }

    public static JwtUtil.AuthUser get() {
        return HOLDER.get();
    }

    public static Long userId() {
        JwtUtil.AuthUser u = HOLDER.get();
        return u == null ? null : u.userId();
    }

    public static String role() {
        JwtUtil.AuthUser u = HOLDER.get();
        return u == null ? null : u.role();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
