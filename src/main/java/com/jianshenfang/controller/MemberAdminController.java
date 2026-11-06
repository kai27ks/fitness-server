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
    public Result<AdminDtos.MemberCreateVO> create(@Valid @RequestBody AdminDtos.MemberCreate req) {
        // Q13：教练买课时也可建档，并把返回的认领码线下交给会员
        AuthUtil.requireStaff();
        return Result.ok(memberService.create(req));
    }

    @GetMapping("/{id}")
    public Result<?> detail(@PathVariable Long id) {
        AuthUtil.requireStaff();
        return Result.ok(memberService.detail(id));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody AdminDtos.MemberUpdate req) {
        // 教练也需修正建档手机号（会员认领失败时的主要补救手段）；
        // 改绑教练、课包增购/课时调整等高危操作仍保持 requireAdmin()
        AuthUtil.requireStaff();
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
