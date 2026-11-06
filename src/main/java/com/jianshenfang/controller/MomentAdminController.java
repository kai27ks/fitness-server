package com.jianshenfang.controller;

import com.jianshenfang.auth.AuthUtil;
import com.jianshenfang.common.Result;
import com.jianshenfang.dto.MomentDtos;
import com.jianshenfang.service.MomentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理端审核：支持单张与批量；批量逐张独立处理，失败项带原因返回 */
@RestController
@RequestMapping("/api/admin/moments")
@RequiredArgsConstructor
public class MomentAdminController {

    private final MomentService momentService;

    @GetMapping
    public Result<MomentDtos.PageVO<MomentDtos.MediaVO>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        AuthUtil.requireAdmin();
        return Result.ok(momentService.adminList(status, page, size));
    }

    @PostMapping("/{id}/review")
    public Result<Void> review(@PathVariable Long id, @Valid @RequestBody MomentDtos.ReviewRequest req) {
        AuthUtil.requireAdmin();
        momentService.review(id, req);
        return Result.ok();
    }

    @PostMapping("/batch-review")
    public Result<MomentDtos.BatchReviewResult> batchReview(@Valid @RequestBody MomentDtos.BatchReviewRequest req) {
        AuthUtil.requireAdmin();
        return Result.ok(momentService.batchReview(req));
    }
}
