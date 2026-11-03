package com.jianshenfang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("coach_qualification")
public class CoachQualification {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long coachId;
    private String name;
    private String certNo;
    private String issuer;
    private String image;
    private LocalDate expireDate;
    private LocalDateTime createTime;
}
