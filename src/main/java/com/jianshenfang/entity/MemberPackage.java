package com.jianshenfang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("member_package")
public class MemberPackage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long memberId;
    private Long courseId;
    private Integer totalSessions;
    private Integer remainSessions;
    private BigDecimal totalPrice;
    private LocalDate startDate;
    private LocalDate expireDate;
    private String status;
    private LocalDateTime createTime;
}
