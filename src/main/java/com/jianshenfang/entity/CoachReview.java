package com.jianshenfang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 会员对教练的满意度评价：一次课只能评一次，1~5 星 */
@Data
@TableName("coach_review")
public class CoachReview {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long memberId;
    private Long coachId;
    private Long bookingId;
    /** 满意度：1~5 星 */
    private Integer rating;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
