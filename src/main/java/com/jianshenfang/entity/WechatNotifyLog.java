package com.jianshenfang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wechat_notify_log")
public class WechatNotifyLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 幂等键：scene:bizId:openid */
    private String dedupKey;
    private String scene;
    private String openid;
    private Long memberId;
    private Long bizId;
    private String templateId;
    private String msgid;
    private Integer errcode;
    private String errmsg;
    /** SUCCESS / FAILED / SKIPPED */
    private String status;
    private Integer retryCount;
    private String content;
    private LocalDateTime createTime;
}
