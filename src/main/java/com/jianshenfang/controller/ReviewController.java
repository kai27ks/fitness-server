package com.jianshenfang.controller;

import com.jianshenfang.auth.AuthContext;
import com.jianshenfang.auth.AuthUtil;
import com.jianshenfang.common.Result;
import com.jianshenfang.dto.ReviewDtos;
import com.jianshenfang.entity.Coach;
import com.jianshenfang.service.CoachAdminService;
import com.jianshenfang.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 会员对教练的满意度评价（目前只有「满意度」1 个维度，1~5 星）。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;
    private final CoachAdminService coachAdminService;

    /** 会员提交评价：一次课只能评一次 */
    @PostMapping("/bookings/{id}/review")
    public Result<ReviewDtos.CoachRating> submit(@PathVariable Long id,
                                                 @RequestBody(required = false) ReviewDtos.ReviewCreate req) {
        return Result.ok(reviewService.submit(AuthContext.userId(), id, req == null ? null : req.rating()));
    }

    /** 教练评分汇总（会员端教练详情展示） */
    @GetMapping("/coaches/{id}/rating")
    public Result<ReviewDtos.CoachRating> rating(@PathVariable Long id) {
        return Result.ok(reviewService.coachRating(id));
    }

    /** 当前登录教练收到的评价（教练端「我的」） */
    @GetMapping("/coaches/me/reviews")
    public Result<ReviewDtos.CoachReviewPage> myReviews(@RequestParam(required = false) Integer limit) {
        AuthUtil.requireStaff();
        Coach coach = coachAdminService.currentCoach();
        return Result.ok(reviewService.coachReviews(coach.getId(), limit));
    }

    /** 管理端点评记录：全部点评分页，可按教练过滤（会员姓名不脱敏，带手机号） */
    @GetMapping("/admin/reviews")
    public Result<ReviewDtos.AdminReviewPage> adminReviews(@RequestParam(required = false) Long coachId,
                                                           @RequestParam(required = false) Integer page,
                                                           @RequestParam(required = false) Integer size) {
        AuthUtil.requireAdmin();
        return Result.ok(reviewService.adminReviews(coachId, page, size));
    }
}
