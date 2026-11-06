package com.jianshenfang.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 复合主键表，仅由 MomentDailyQuotaMapper 的自定义 SQL 操作 */
@Data
@TableName("moment_daily_quota")
public class MomentDailyQuota {
    private Long memberId;
    private LocalDate uploadDay;
    private Integer usedCount;
    private LocalDateTime updateTime;
}
