package com.jianshenfang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("member_claim_code")
public class MemberClaimCode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long memberId;
    private String code;
    private LocalDateTime expireAt;
    private Integer failCount;
    private LocalDateTime usedAt;
    private LocalDateTime createTime;
}
