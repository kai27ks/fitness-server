package com.jianshenfang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wechat_temp_token")
public class WechatTempToken {
    /** 随机 token，STATE 与 CLAIM_TICKET 共用此表 */
    @TableId(type = IdType.INPUT)
    private String token;
    private String type;
    private String payload;
    private LocalDateTime expireAt;
    private LocalDateTime usedAt;
    private LocalDateTime createTime;
}
