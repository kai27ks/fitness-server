package com.jianshenfang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("media")
public class Media {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String DELETED = "DELETED";

    public static final String AUDIT_SKIPPED = "SKIPPED";
    public static final String AUDIT_PASS = "PASS";
    public static final String AUDIT_REVIEW = "REVIEW";
    public static final String AUDIT_BLOCK = "BLOCK";
    public static final String AUDIT_ERROR = "ERROR";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long memberId;
    private String authorName;
    private String title;
    private String type;
    private String url;
    private String thumbUrl;
    private Integer width;
    private Integer height;
    private Long sourceSize;
    private Long outputSize;
    private String sourceFormat;
    private String batchId;
    private LocalDate uploadDay;
    private String status;
    private String rejectReason;
    private Long reviewBy;
    private LocalDateTime reviewAt;
    private String auditStatus;
    private String auditSuggestion;
    private String auditLabel;
    private Integer auditScore;
    private String auditRequestId;
    private LocalDateTime auditAt;
    private Integer commentCount;
    private Integer duration;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
