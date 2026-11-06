package com.jianshenfang.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public final class MomentDtos {

    private MomentDtos() {}

    public record QuotaVO(int used, int limit, boolean canUpload) {}

    /** 两个审核开关的当前组合；directPublish=true 表示两关都关、上传即展示 */
    public record ConfigVO(boolean machineAuditEnabled, boolean needReview, boolean directPublish) {}

    public record MediaVO(Long id, Long memberId, String authorName, String title,
                          String url, String thumbUrl, int width, int height,
                          long sourceSize, long outputSize, String sourceFormat,
                          String batchId, String status, String rejectReason,
                          String auditStatus, String auditLabel, Integer auditScore,
                          String createTime, int commentCount, boolean mine) {}

    public record PageVO<T>(List<T> list, long total, int page, int size) {}

    public record CommentVO(Long id, Long mediaId, Long userId, String authorName, String authorRole,
                            Long rootId, Long parentId, String replyToName, String content,
                            String status, String createTime, boolean mine) {}

    public record CommentGroupVO(CommentVO root, List<CommentVO> replies) {}

    public record DetailVO(MediaVO media, List<CommentGroupVO> comments, long totalComments) {}

    public record CommentAdd(@NotBlank(message = "评论内容不能为空") String content,
                             Long parentId, Long rootId, Long replyToUserId) {}

    public record ReviewRequest(@NotBlank(message = "审核动作必填") String action, String reason) {}

    public record BatchReviewRequest(@NotEmpty(message = "请至少选择一张图片") List<Long> ids,
                                     @NotBlank(message = "审核动作必填") String action,
                                     String reason) {}

    public record BatchReviewItem(Long id, String reason) {}

    public record BatchReviewResult(int total, List<Long> successIds, List<BatchReviewItem> failed) {}
}
