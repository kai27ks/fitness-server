package com.jianshenfang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("booking")
public class Booking {
    public static final String BOOKED = "BOOKED";
    public static final String CHECKED_IN = "CHECKED_IN";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELED = "CANCELED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long memberId;
    private Long coachId;
    private Long courseId;
    private Long packageId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String status;
    private String cancelBy;
    private String cancelReason;
    private LocalDateTime canceledAt;
    private LocalDateTime checkedInAt;
    private LocalDateTime createTime;
}
