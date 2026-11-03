package com.jianshenfang.controller;

import com.jianshenfang.auth.AuthContext;
import com.jianshenfang.auth.AuthUtil;
import com.jianshenfang.common.Result;
import com.jianshenfang.dto.AdminDtos;
import com.jianshenfang.service.CoachAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CoachAdminController {

    private final CoachAdminService coachService;

    @GetMapping("/admin/coaches")
    public Result<?> list(@RequestParam(required = false) Long status) {
        AuthUtil.requireStaff();
        return Result.ok(coachService.list(status));
    }

    @PostMapping("/admin/coaches")
    public Result<?> create(@Valid @RequestBody AdminDtos.CoachCreate req) {
        AuthUtil.requireAdmin();
        return Result.ok(coachService.create(req));
    }

    @PutMapping("/admin/coaches/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody AdminDtos.CoachUpdate req) {
        AuthUtil.requireAdmin();
        coachService.update(id, req);
        return Result.ok();
    }

    @GetMapping("/coaches/me")
    public Result<?> me() {
        AuthUtil.requireStaff();
        return Result.ok(coachService.currentCoach());
    }

    @GetMapping("/coaches/{id}/qualifications")
    public Result<?> qualifications(@PathVariable Long id) {
        AuthUtil.requireStaff();
        return Result.ok(coachService.qualifications(id));
    }

    @PostMapping("/coaches/{id}/qualifications")
    public Result<?> addQualification(@PathVariable Long id, @Valid @RequestBody AdminDtos.QualificationAdd req) {
        AuthUtil.requireAdmin();
        return Result.ok(coachService.addQualification(id, req));
    }

    @DeleteMapping("/qualifications/{qid}")
    public Result<Void> deleteQualification(@PathVariable Long qid) {
        AuthUtil.requireAdmin();
        coachService.deleteQualification(qid);
        return Result.ok();
    }

    @GetMapping("/coaches/{id}/leaves")
    public Result<?> leaves(@PathVariable Long id) {
        AuthUtil.requireStaff();
        return Result.ok(coachService.leaves(id));
    }

    @PostMapping("/coaches/{id}/leaves")
    public Result<?> createLeave(@PathVariable Long id, @Valid @RequestBody AdminDtos.LeaveCreate req) {
        AuthUtil.requireStaff();
        return Result.ok(coachService.createLeave(id, req));
    }

    @GetMapping("/coaches/{id}/leave-conflict")
    public Result<?> leaveConflict(@PathVariable Long id,
                                   @RequestParam String startTime, @RequestParam String endTime) {
        AuthUtil.requireStaff();
        return Result.ok(coachService.leaveConflict(id, startTime, endTime));
    }

    @DeleteMapping("/leaves/{lid}")
    public Result<Void> deleteLeave(@PathVariable Long lid) {
        AuthUtil.requireStaff();
        coachService.deleteLeave(lid);
        return Result.ok();
    }
}
