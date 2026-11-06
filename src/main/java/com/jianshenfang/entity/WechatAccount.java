package com.jianshenfang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wechat_account")
public class WechatAccount {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String appid;
    private String openid;
    private String unionid;
    private String avatarUrl;
    private String nickname;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
