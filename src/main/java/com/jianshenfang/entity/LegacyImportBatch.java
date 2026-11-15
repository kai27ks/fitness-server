package com.jianshenfang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 旧会员资料导入批次：一次上传一条，便于对账与按批次回滚 */
@Data
@TableName("legacy_import_batch")
public class LegacyImportBatch {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String fileName;
    private Integer totalRows;
    private Integer memberCount;
    private Integer packageCount;
    private Integer skippedCount;
    private Integer failedCount;
    private Long operatorId;
    private LocalDateTime createTime;
}
