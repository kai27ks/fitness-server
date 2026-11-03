package com.jianshenfang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notify_log")
public class NotifyLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String scene;
    private String receiver;
    private Long bizId;
    private String content;
    private String status;
    private String failReason;
    private LocalDateTime createTime;
}
