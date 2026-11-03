package com.jianshenfang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("coach_leave")
public class CoachLeave {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long coachId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String reason;
    private Integer status;
    private LocalDateTime createTime;
}
