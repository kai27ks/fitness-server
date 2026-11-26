package com.jianshenfang.dto;

import java.util.List;

public final class ReviewDtos {
    private ReviewDtos() {}

    /** 会员提交满意度：1~5 星（当前只有一个评价维度） */
    public record ReviewCreate(Integer rating) {}

    /** 教练评分汇总 */
    public record CoachRating(int count, double average) {}

    /** 单条评价（教练 / 管理端查看） */
    public record ReviewItem(Long id, String memberName, int rating, String courseName,
                             String coachName, String bookingTime, String createTime) {}

    public record CoachReviewPage(CoachRating summary, List<ReviewItem> items) {}

    /** 管理端单条点评：会员姓名不脱敏，并带手机号便于定位 */
    public record AdminReviewItem(Long id, String memberName, String memberPhone, String coachName,
                                  String courseName, int rating, String bookingTime, String createTime) {}

    /** 管理端点评分页：summary 为当前筛选范围（全部 / 指定教练）的汇总 */
    public record AdminReviewPage(int total, int page, int size,
                                  CoachRating summary, List<AdminReviewItem> items) {}
}
