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
    /** 课包归属教练（旧数据迁移用，可空；新购课包可不填） */
    private Long coachId;
    private Integer totalSessions;
    private Integer remainSessions;
    private BigDecimal totalPrice;
    private LocalDate startDate;
    private LocalDate expireDate;
    private String status;
    /** 来源：NEW 新购 / LEGACY_IMPORT 旧数据迁移 */
    private String source;
    /** 旧数据迁移批次，便于按批次回滚 */
    private Long importBatchId;
    private LocalDateTime createTime;
}
