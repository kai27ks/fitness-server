package com.jianshenfang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 旧会员资料的原始行归档：Excel 有什么就存什么，便于对账与回溯 */
@Data
@TableName("legacy_import_row")
public class LegacyImportRow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long batchId;
    private Integer rowNo;
    private String phone;
    private String name;
    private String coachName;
    private String cardName;
    private String courseType;
    private Integer remain;
    private String cardNo;
    private String payType;
    private String salesName;
    private String sourceChannel;
    private String remark;
    /** 映射到的会员，失败/跳过为 null */
    private Long memberId;
    /** 映射到的课包，失败/跳过为 null */
    private Long packageId;
    /** OK / SKIPPED / FAILED */
    private String status;
    private String errMsg;
}
