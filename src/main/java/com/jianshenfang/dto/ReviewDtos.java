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
}
