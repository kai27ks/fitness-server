package com.jianshenfang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("media_comment")
public class MediaComment {

    public static final String NORMAL = "NORMAL";
    public static final String DELETED = "DELETED";
    public static final String DELETED_TEXT = "该评论已删除";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long mediaId;
    private Long userId;
    private String authorRole;
    private String authorName;
    private Long rootId;
    private Long parentId;
    private Long replyToUserId;
    private String replyToName;
    private String content;
    private String status;
    private LocalDateTime createTime;
}
