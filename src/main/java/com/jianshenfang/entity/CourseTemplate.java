package com.jianshenfang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("course_template")
public class CourseTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Integer durationMin;
    private Integer defaultSessions;
    private Integer status;
    private LocalDateTime createTime;
}
