package com.jianshenfang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wechat_claim_request")
public class WechatClaimRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String appid;
    private String openid;
    private String unionid;
    private String avatarUrl;
    private String nickname;
    private String phoneInput;
    private String status;
    private String handleNote;
    private Long handledBy;
    private LocalDateTime handledAt;
    private LocalDateTime createTime;
}
