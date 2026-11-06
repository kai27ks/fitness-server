package com.jianshenfang.controller;

import com.jianshenfang.common.Result;
import com.jianshenfang.dto.MomentDtos;
import com.jianshenfang.service.MomentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/moments")
@RequiredArgsConstructor
public class MomentController {

    private final MomentService momentService;

    /** 当前机审/人审两个开关的组合，供前端展示准确的流程文案（不含密钥） */
    @GetMapping("/config")
    public Result<MomentDtos.ConfigVO> config() {
        return Result.ok(momentService.config());
    }

    /** 今日额度，仅用于前端展示，最终以后端判定为准 */
    @GetMapping("/quota")
    public Result<MomentDtos.QuotaVO> quota() {
        return Result.ok(momentService.quota());
    }

    /**
     * 单图上传：一次请求一张图、一次图片审核。前端一张一张传时就一张一张审，
     * 有问题的图片只打回当前这一张（返回 400 + 具体原因），其余不受影响。
     */
    @PostMapping("/images")
    public Result<MomentDtos.MediaVO> upload(@RequestParam("file") MultipartFile file,
                                             @RequestParam(required = false) String title,
                                             @RequestParam(required = false) String batchId) {
        return Result.ok(momentService.upload(file, title, batchId));
    }

    @GetMapping
    public Result<MomentDtos.PageVO<MomentDtos.MediaVO>> list(
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(momentService.list(scope, page, size));
    }

    @GetMapping("/{id}")
    public Result<MomentDtos.DetailVO> detail(@PathVariable Long id) {
        return Result.ok(momentService.detail(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        momentService.deleteMedia(id);
        return Result.ok();
    }

    @GetMapping("/{id}/comments")
    public Result<MomentDtos.PageVO<MomentDtos.CommentGroupVO>> comments(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(momentService.comments(id, page, size));
    }

    @PostMapping("/{id}/comments")
    public Result<MomentDtos.CommentVO> addComment(@PathVariable Long id,
                                                   @Valid @RequestBody MomentDtos.CommentAdd req) {
        return Result.ok(momentService.addComment(id, req));
    }

    @DeleteMapping("/comments/{cid}")
    public Result<Void> deleteComment(@PathVariable Long cid) {
        momentService.deleteComment(cid);
        return Result.ok();
    }
}
