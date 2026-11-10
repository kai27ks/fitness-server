package com.jianshenfang.controller;

import com.jianshenfang.auth.AuthContext;
import com.jianshenfang.common.Result;
import com.jianshenfang.config.SupportProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/support")
@RequiredArgsConstructor
public class SupportController {
    private final SupportProperties properties;

    public record Contact(String title, String description, String developerName, String phone, String email) {}

    @GetMapping
    public Result<Contact> contact() {
        String role = AuthContext.role();
        if (!properties.isEnabled() || !("MEMBER".equals(role) || "COACH".equals(role))) {
            return Result.ok(null);
        }
        String phone = clean(properties.getPhone());
        String email = clean(properties.getEmail());
        if (phone.isEmpty() && email.isEmpty()) return Result.ok(null);
        String title = clean(properties.getTitle());
        return Result.ok(new Contact(title.isEmpty() ? "系统维护与技术支持" : title,
                clean(properties.getDescription()), clean(properties.getDeveloperName()), phone, email));
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
