package com.jianshenfang.controller;

import com.jianshenfang.auth.AuthUtil;
import com.jianshenfang.common.Result;
import com.jianshenfang.dto.AdminDtos;
import com.jianshenfang.service.CourseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/courses")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;

    @GetMapping
    public Result<?> list() {
        return Result.ok(courseService.list());
    }

    @PostMapping
    public Result<?> create(@Valid @RequestBody AdminDtos.CourseCreate req) {
        AuthUtil.requireAdmin();
        return Result.ok(courseService.create(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody AdminDtos.CourseUpdate req) {
        AuthUtil.requireAdmin();
        courseService.update(id, req);
        return Result.ok();
    }

    @PostMapping("/{id}/toggle")
    public Result<Void> toggle(@PathVariable Long id) {
        AuthUtil.requireAdmin();
        courseService.toggle(id);
        return Result.ok();
    }
}
