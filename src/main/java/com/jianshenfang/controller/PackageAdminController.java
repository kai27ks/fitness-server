package com.jianshenfang.controller;

import com.jianshenfang.auth.AuthUtil;
import com.jianshenfang.common.Result;
import com.jianshenfang.dto.AdminDtos;
import com.jianshenfang.service.AdminMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 会员课包管理（管理端独立入口）。课时增减沿用会员详情下的 adjust 接口。
 */
@RestController
@RequestMapping("/api/admin/packages")
@RequiredArgsConstructor
public class PackageAdminController {

    private final AdminMemberService memberService;

    @GetMapping
    public Result<?> list(@RequestParam(required = false) String keyword) {
        AuthUtil.requireStaff();
        return Result.ok(memberService.allPackages(keyword));
    }

    @PostMapping("/{id}/adjust")
    public Result<Void> adjust(@PathVariable Long id, @RequestBody AdminDtos.AdjustSessions req) {
        AuthUtil.requireAdmin();
        memberService.adjustSessions(id, req);
        return Result.ok();
    }
}
