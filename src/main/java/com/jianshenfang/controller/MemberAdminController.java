package com.jianshenfang.controller;

import com.jianshenfang.auth.AuthUtil;
import com.jianshenfang.common.Result;
import com.jianshenfang.dto.AdminDtos;
import com.jianshenfang.service.AdminMemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/members")
@RequiredArgsConstructor
public class MemberAdminController {

    private final AdminMemberService memberService;

    @GetMapping
    public Result<?> list(@RequestParam(required = false) String keyword, @RequestParam(required = false) Long coachId) {
        AuthUtil.requireStaff();
        return Result.ok(memberService.list(keyword, coachId));
    }

    @PostMapping
    public Result<?> create(@Valid @RequestBody AdminDtos.MemberCreate req) {
        AuthUtil.requireAdmin();
        return Result.ok(memberService.create(req));
    }

    @GetMapping("/{id}")
    public Result<?> detail(@PathVariable Long id) {
        AuthUtil.requireStaff();
        return Result.ok(memberService.detail(id));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody AdminDtos.MemberUpdate req) {
        AuthUtil.requireAdmin();
        memberService.updateMember(id, req);
        return Result.ok();
    }

    @PutMapping("/{id}/coach")
    public Result<Void> rebindCoach(@PathVariable Long id, @RequestBody AdminDtos.BindCoach req) {
        AuthUtil.requireAdmin();
        memberService.rebindCoach(id, req.coachId(), req.remark());
        return Result.ok();
    }

    @PostMapping("/{id}/packages")
    public Result<?> addPackage(@PathVariable Long id, @Valid @RequestBody AdminDtos.PackageAdd req) {
        AuthUtil.requireAdmin();
        return Result.ok(memberService.addPackage(id, req));
    }

    @PostMapping("/packages/{pid}/adjust")
    public Result<Void> adjust(@PathVariable Long pid, @RequestBody AdminDtos.AdjustSessions req) {
        AuthUtil.requireAdmin();
        memberService.adjustSessions(pid, req);
        return Result.ok();
    }
}
