package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.auth.AuthContext;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.config.MomentProperties;
import com.jianshenfang.dto.MomentDtos;
import com.jianshenfang.entity.Coach;
import com.jianshenfang.entity.Member;
import com.jianshenfang.entity.Media;
import com.jianshenfang.entity.MediaComment;
import com.jianshenfang.mapper.CoachMapper;
import com.jianshenfang.mapper.MemberMapper;
import com.jianshenfang.mapper.MediaCommentMapper;
import com.jianshenfang.mapper.MediaMapper;
import com.jianshenfang.mapper.MomentDailyQuotaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会员风采：图片压缩 → 逐张机审 → 原子额度 → 落盘落库 → 广场/详情/评论/审核。
 *
 * <p>逐张粒度：一次请求只处理一张图片，机审也只调用一次；前端一张一张上传即一张一张审核，
 * 有问题的那张单独抛错（打回），不影响同批次其他图片。管理端另提供批量审核接口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MomentService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final MediaMapper mediaMapper;
    private final MediaCommentMapper commentMapper;
    private final MomentDailyQuotaMapper quotaMapper;
    private final MemberMapper memberMapper;
    private final CoachMapper coachMapper;
    private final OperationLogService logService;
    private final ImageAuditService auditService;
    private final MomentProperties props;

    // ==================== 额度 ====================

    /** 当前两个审核开关的组合，供前端展示准确流程文案；纯内存读配置，不查库、不含密钥 */
    public MomentDtos.ConfigVO config() {
        boolean machine = auditService.enabled();
        boolean review = props.isNeedReview();
        return new MomentDtos.ConfigVO(machine, review, !machine && !review);
    }

    public MomentDtos.QuotaVO quota() {
        Long memberId = myMemberIdOrNull();
        int used = memberId == null ? 0 : usedToday(memberId);
        return new MomentDtos.QuotaVO(used, props.getDailyLimit(),
                memberId != null && used < props.getDailyLimit());
    }

    private int usedToday(Long memberId) {
        Integer used = quotaMapper.used(memberId, LocalDate.now(ZONE).toString());
        return used == null ? 0 : used;
    }

    // ==================== 上传（逐张） ====================

    /**
     * 单图上传：校验 → 压缩 → 腾讯云机审 → 额度 + 落盘 + 落库。
     * 机审 BLOCK（或配置 review-as-reject）时直接抛业务异常，只打回当前这一张，
     * 不落库、不占额度；机审异常按 fail-open 决定是否放行并转人工。
     */
    @Transactional
    public MomentDtos.MediaVO upload(MultipartFile file, String title, String batchId) {
        checkEnabled();
        Member member = requireMember();
        String cleanTitle = cleanTitle(title);

        byte[] source = readSource(file);
        String format = detectFormat(source);
        if (format == null) {
            throw new BusinessException("仅支持 JPG / PNG / WebP 图片，本期不支持视频");
        }
        Compressed img = compress(source, format);
        String dataId = UUID.randomUUID().toString().replace("-", "");

        // ===== 逐张调用腾讯云图片审核：一张图片 = 一次 ImageModeration =====
        ImageAuditService.AuditResult audit = auditService.audit(img.full(), dataId);
        String auditStatus = audit.status();
        if (Media.AUDIT_BLOCK.equals(auditStatus)) {
            throw new BusinessException("当前图片已被打回：" + audit.message());
        }
        if (Media.AUDIT_ERROR.equals(auditStatus) && !props.getAudit().isFailOpen()) {
            throw new BusinessException("当前图片审核失败，请稍后重试：" + audit.message());
        }

        // 额度与落库同一事务：超额抛异常会连额度一起回滚，并发连点也不会突破 4 张
        String day = LocalDate.now(ZONE).toString();
        quotaMapper.consume(member.getId(), day);
        if (usedToday(member.getId()) > props.getDailyLimit()) {
            throw new BusinessException("今日 " + props.getDailyLimit() + " 张已用完，每日 0 点重置");
        }

        Stored stored = store(img);
        Media m = new Media();
        m.setMemberId(member.getId());
        m.setAuthorName(member.getName());
        m.setTitle(cleanTitle);
        m.setType("IMAGE");
        m.setUrl(stored.url());
        m.setThumbUrl(stored.thumbUrl());
        m.setWidth(img.width());
        m.setHeight(img.height());
        m.setSourceSize((long) source.length);
        m.setOutputSize((long) img.full().length);
        m.setSourceFormat(format);
        m.setBatchId(batchId);
        m.setUploadDay(LocalDate.now(ZONE));
        // 两个审核开关共同决定落库状态，四种组合见 resolveInitialStatus 的注释表
        m.setStatus(resolveInitialStatus(auditStatus));
        m.setAuditStatus(auditStatus);
        m.setAuditSuggestion(audit.suggestion());
        m.setAuditLabel(audit.label());
        m.setAuditScore(audit.score());
        m.setAuditRequestId(audit.requestId());
        m.setAuditAt(LocalDateTime.now());
        m.setCommentCount(0);
        m.setCreateTime(LocalDateTime.now());
        try {
            mediaMapper.insert(m);
        } catch (Exception e) {
            deleteQuietly(stored.fullPath(), stored.thumbPath());
            throw e;
        }
        return toVO(m, member.getId());
    }

    /**
     * 上传落库的初始状态：两个独立开关 + 机审结论的唯一决策点。
     *
     * <pre>
     * 机审 人审 | 机审通过 / 跳过机审        | 机审疑似 REVIEW
     * 开   开  | PENDING（管理员通过才展示） | PENDING 待人工复核
     * 开   关  | APPROVED 立即展示          | PENDING 安全兜底
     * 关   开  | PENDING 待人工             | 不适用
     * 关   关  | APPROVED 上传即展示        | 不适用
     * </pre>
     *
     * BLOCK 与 fail-open=false 的 ERROR 在调用本方法前已抛异常打回，不会走到这里。
     * REVIEW 即使人审开关关闭也保留 PENDING：管理端审核接口始终可用，不会留下无人可审的死局；
     * 若希望疑似图直接打回，把 app.moments.audit.review-as-reject 设为 true。
     */
    private String resolveInitialStatus(String auditStatus) {
        if (Media.AUDIT_REVIEW.equals(auditStatus)) {
            return Media.PENDING;
        }
        return props.isNeedReview() ? Media.PENDING : Media.APPROVED;
    }

    private byte[] readSource(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException("请选择要发布的图片");
        if (file.getSize() > props.getMaxSourceBytes()) {
            throw new BusinessException(String.format("图片过大（%.1f MB），超过 %.0f MB 上限，请换一张",
                    file.getSize() / 1048576.0, props.getMaxSourceBytes() / 1048576.0));
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("图片读取失败，请换一张");
        }
    }

    private String cleanTitle(String title) {
        if (title == null) return null;
        String t = title.replaceAll("[\\x00-\\x1F\\x7F]", " ").trim();
        if (t.length() > props.getTitleMaxLength()) {
            throw new BusinessException("配文最多 " + props.getTitleMaxLength() + " 字");
        }
        return t.isEmpty() ? null : t;
    }

    // ==================== 列表 / 详情 ====================

    public MomentDtos.PageVO<MomentDtos.MediaVO> list(String scope, int page, int size) {
        checkEnabled();
        int p = Math.max(1, page), s = Math.min(50, Math.max(1, size));
        Long my = myMemberIdOrNull();
        LambdaQueryWrapper<Media> w = new LambdaQueryWrapper<>();
        if ("mine".equals(scope)) {
            if (my == null) return new MomentDtos.PageVO<>(List.of(), 0, p, s);
            w.eq(Media::getMemberId, my).ne(Media::getStatus, Media.DELETED);
        } else {
            // 广场：已通过的全部可见；自己待审/被驳回的仅自己可见
            if (my == null) {
                w.eq(Media::getStatus, Media.APPROVED);
            } else {
                Long finalMy = my;
                w.and(x -> x.eq(Media::getStatus, Media.APPROVED)
                        .or(y -> y.eq(Media::getMemberId, finalMy)
                                .in(Media::getStatus, List.of(Media.PENDING, Media.REJECTED))));
            }
        }
        w.orderByDesc(Media::getId);
        long total = mediaMapper.selectCount(w);
        List<MomentDtos.MediaVO> list = mediaMapper.selectList(w.last("LIMIT " + (p - 1) * s + ", " + s))
                .stream().map(m -> toVO(m, my)).toList();
        return new MomentDtos.PageVO<>(list, total, p, s);
    }

    public MomentDtos.DetailVO detail(Long id) {
        checkEnabled();
        Media m = visibleMedia(id);
        MomentDtos.PageVO<MomentDtos.CommentGroupVO> comments = comments(id, 1, 200);
        return new MomentDtos.DetailVO(toVO(m, myMemberIdOrNull()), comments.list(), comments.total());
    }

    private Media visibleMedia(Long id) {
        Media m = mediaMapper.selectById(id);
        if (m == null || Media.DELETED.equals(m.getStatus())) {
            throw new BusinessException("图片不存在或已删除");
        }
        Long my = myMemberIdOrNull();
        boolean admin = "ADMIN".equals(AuthContext.role());
        if (!Media.APPROVED.equals(m.getStatus()) && !admin && (my == null || !my.equals(m.getMemberId()))) {
            throw new BusinessException("图片未通过审核，暂不可查看");
        }
        return m;
    }

    // ==================== 评论 / 楼中楼 ====================

    public MomentDtos.PageVO<MomentDtos.CommentGroupVO> comments(Long mediaId, int page, int size) {
        int p = Math.max(1, page), s = Math.min(50, Math.max(1, size));
        List<MediaComment> all = commentMapper.selectList(new LambdaQueryWrapper<MediaComment>()
                .eq(MediaComment::getMediaId, mediaId).orderByAsc(MediaComment::getId));
        // 一级评论按时间倒序，楼内回复按时间升序；不做第三层嵌套
        List<MediaComment> roots = all.stream().filter(c -> c.getRootId() == null)
                .sorted(Comparator.comparing(MediaComment::getId).reversed()).toList();
        long total = roots.size();
        List<MediaComment> pageRoots = roots.stream().skip((long) (p - 1) * s).limit(s).toList();
        Map<Long, List<MomentDtos.CommentVO>> replies = new LinkedHashMap<>();
        for (MediaComment c : all) {
            if (c.getRootId() == null) continue;
            replies.computeIfAbsent(c.getRootId(), k -> new ArrayList<>())
                    .add(toCommentVO(c));
        }
        List<MomentDtos.CommentGroupVO> groups = pageRoots.stream()
                .map(r -> new MomentDtos.CommentGroupVO(toCommentVO(r),
                        replies.getOrDefault(r.getId(), List.of())))
                .toList();
        return new MomentDtos.PageVO<>(groups, total, p, s);
    }

    public MomentDtos.CommentVO addComment(Long mediaId, MomentDtos.CommentAdd req) {
        checkEnabled();
        visibleMedia(mediaId);
        String content = req.content().replaceAll("[\\x00-\\x1F\\x7F]", " ").trim();
        if (content.isEmpty()) throw new BusinessException("评论内容不能为空");
        if (content.length() > props.getCommentMaxLength()) {
            throw new BusinessException("评论最多 " + props.getCommentMaxLength() + " 字");
        }
        if (props.getCommentMaxPerMinute() > 0) {
            Long cnt = commentMapper.selectCount(new LambdaQueryWrapper<MediaComment>()
                    .eq(MediaComment::getUserId, AuthContext.userId())
                    .ge(MediaComment::getCreateTime, LocalDateTime.now().minusMinutes(1)));
            if (cnt != null && cnt >= props.getCommentMaxPerMinute()) {
                throw new BusinessException("评论太快了，请稍后再发");
            }
        }

        MediaComment c = new MediaComment();
        c.setMediaId(mediaId);
        c.setUserId(AuthContext.userId());
        c.setAuthorRole(AuthContext.role());
        c.setAuthorName(displayName());
        c.setContent(content);
        c.setStatus(MediaComment.NORMAL);
        c.setCreateTime(LocalDateTime.now());

        if (req.parentId() != null) {
            MediaComment parent = commentMapper.selectById(req.parentId());
            if (parent == null || !mediaId.equals(parent.getMediaId())) {
                throw new BusinessException("被回复的评论不存在");
            }
            // 对「回复」再回复，仍挂同一个楼层，显示为「A 回复 B：…」
            c.setRootId(parent.getRootId() == null ? parent.getId() : parent.getRootId());
            c.setParentId(parent.getId());
            c.setReplyToUserId(parent.getUserId());
            c.setReplyToName(parent.getAuthorName());
        }
        commentMapper.insert(c);
        mediaMapper.incrCommentCount(mediaId);
        return toCommentVO(c);
    }

    public void deleteComment(Long commentId) {
        MediaComment c = commentMapper.selectById(commentId);
        if (c == null) throw new BusinessException("评论不存在");
        boolean admin = "ADMIN".equals(AuthContext.role());
        if (!admin && !AuthContext.userId().equals(c.getUserId())) {
            throw new BusinessException("只能删除自己的评论");
        }
        c.setStatus(MediaComment.DELETED);
        c.setContent(MediaComment.DELETED_TEXT);
        commentMapper.updateById(c);
        mediaMapper.decrCommentCount(c.getMediaId());
    }

    public void deleteMedia(Long id) {
        Media m = mediaMapper.selectById(id);
        if (m == null) throw new BusinessException("图片不存在");
        Long my = myMemberIdOrNull();
        boolean admin = "ADMIN".equals(AuthContext.role());
        if (!admin && (my == null || !my.equals(m.getMemberId()))) {
            throw new BusinessException("只能删除自己发布的图片");
        }
        m.setStatus(Media.DELETED);
        mediaMapper.updateById(m);
        commentMapper.softDeleteByMedia(id);
        logService.log("DELETE_MOMENT", "MEDIA", id, "删除风采图片及其评论（软删）");
    }

    // ==================== 管理端：单张 / 批量审核 ====================

    public MomentDtos.PageVO<MomentDtos.MediaVO> adminList(String status, int page, int size) {
        int p = Math.max(1, page), s = Math.min(50, Math.max(1, size));
        LambdaQueryWrapper<Media> w = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) w.eq(Media::getStatus, status);
        w.ne(Media::getStatus, Media.DELETED).orderByAsc(Media::getId);
        long total = mediaMapper.selectCount(w);
        Long my = myMemberIdOrNull();
        List<MomentDtos.MediaVO> list = mediaMapper.selectList(w.last("LIMIT " + (p - 1) * s + ", " + s))
                .stream().map(m -> toVO(m, my)).toList();
        return new MomentDtos.PageVO<>(list, total, p, s);
    }

    public void review(Long id, MomentDtos.ReviewRequest req) {
        reviewOne(id, req.action(), req.reason());
    }

    /**
     * 批量审核：逐个独立处理，某张出问题只记录该张的失败原因，其余照常完成，
     * 最后返回 {successIds, failed[{id, reason}]}，前端可精确提示「打回出问题的那张」。
     */
    public MomentDtos.BatchReviewResult batchReview(MomentDtos.BatchReviewRequest req) {
        List<Long> ok = new ArrayList<>();
        List<MomentDtos.BatchReviewItem> failed = new ArrayList<>();
        for (Long id : req.ids()) {
            try {
                reviewOne(id, req.action(), req.reason());
                ok.add(id);
            } catch (BusinessException e) {
                failed.add(new MomentDtos.BatchReviewItem(id, e.getMessage()));
            }
        }
        return new MomentDtos.BatchReviewResult(req.ids().size(), ok, failed);
    }

    private void reviewOne(Long id, String action, String reason) {
        Media m = mediaMapper.selectById(id);
        if (m == null || Media.DELETED.equals(m.getStatus())) {
            throw new BusinessException("图片不存在或已删除");
        }
        if (!Media.PENDING.equals(m.getStatus())) {
            throw new BusinessException("仅待审核图片可操作（当前 " + m.getStatus() + "）");
        }
        if ("APPROVE".equalsIgnoreCase(action)) {
            m.setStatus(Media.APPROVED);
            m.setRejectReason(null);
        } else if ("REJECT".equalsIgnoreCase(action)) {
            String r = reason == null ? "" : reason.trim();
            if (r.isEmpty()) throw new BusinessException("驳回必须填写理由");
            m.setStatus(Media.REJECTED);
            m.setRejectReason(r.length() > 200 ? r.substring(0, 200) : r);
        } else {
            throw new BusinessException("审核动作只能是 APPROVE 或 REJECT");
        }
        m.setReviewBy(AuthContext.userId());
        m.setReviewAt(LocalDateTime.now());
        mediaMapper.updateById(m);
        logService.log("REVIEW_MOMENT", "MEDIA", id,
                ("APPROVE".equalsIgnoreCase(action) ? "批量/单张通过" : "驳回：" + m.getRejectReason()));
    }

    // ==================== 图片处理 ====================

    private record Compressed(byte[] full, int width, int height, byte[] thumb) {}

    private record Stored(String url, String thumbUrl, Path fullPath, Path thumbPath) {}

    private Compressed compress(byte[] source, String format) {
        BufferedImage src = null;
        ImageInputStream iis = null;
        try {
            iis = ImageIO.createImageInputStream(new ByteArrayInputStream(source));
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new BusinessException("不支持该图片格式（仅 JPG / PNG / WebP）");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                long w = reader.getWidth(0), h = reader.getHeight(0);
                if (w * h > props.getMaxSourcePixels()) {
                    throw new BusinessException("图片分辨率过高，请裁切后重试");
                }
                src = reader.read(0, reader.getDefaultReadParam());
            } finally {
                reader.dispose();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("图片解码失败，请换一张（" + format + "）");
        } finally {
            if (iis != null) {
                try { iis.close(); } catch (IOException ignored) {}
            }
        }
        if (src == null) throw new BusinessException("图片解码失败，请换一张");

        // 统一压缩参数：长边降级 × 质量降级，任一档位 ≤1MB 即采用
        int[] edges = new int[]{props.getMaxEdge(), (props.getMaxEdge() + props.getMinEdge()) / 2, props.getMinEdge()};
        List<Double> qualities = new ArrayList<>();
        for (double q = props.getJpegQuality(); q > props.getMinJpegQuality() + 0.001; q -= 0.04) {
            qualities.add(Math.round(q * 100) / 100.0);
        }
        qualities.add(props.getMinJpegQuality());

        byte[] full = null;
        int outW = 0, outH = 0;
        for (int edge : edges) {
            BufferedImage scaled = scale(src, edge);
            for (double q : qualities) {
                byte[] bytes = encodeJpeg(scaled, q);
                if (bytes.length <= props.getMaxOutputBytes()) {
                    full = bytes;
                    outW = scaled.getWidth();
                    outH = scaled.getHeight();
                    break;
                }
            }
            if (full != null) break;
        }
        if (full == null) {
            throw new BusinessException(String.format("压缩后仍超过 %.0f KB，为保证清晰度已停止压缩，请换一张",
                    props.getMaxOutputBytes() / 1024.0));
        }

        // 缩略图：长边 640，尽量 ≤150KB
        BufferedImage thumbImg = scale(src, props.getThumbEdge());
        byte[] thumb = null;
        for (double q = props.getJpegQuality(); q >= 0.60; q -= 0.06) {
            byte[] bytes = encodeJpeg(thumbImg, q);
            if (thumb == null || bytes.length < thumb.length) thumb = bytes;
            if (bytes.length <= props.getThumbMaxBytes()) break;
        }
        return new Compressed(full, outW, outH, thumb);
    }

    private static BufferedImage scale(BufferedImage src, int longEdge) {
        int w = src.getWidth(), h = src.getHeight(), max = Math.max(w, h);
        if (max <= longEdge) return src;                       // 不放大
        double ratio = (double) longEdge / max;
        int nw = Math.max(1, (int) Math.round(w * ratio));
        int nh = Math.max(1, (int) Math.round(h * ratio));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);                               // PNG 透明区转白底
        g.fillRect(0, 0, nw, nh);
        g.drawImage(src, 0, 0, nw, nh, null);                  // 重编码去 EXIF/GPS/ICC
        g.dispose();
        return out;
    }

    private static byte[] encodeJpeg(BufferedImage img, double quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new BusinessException("系统缺少 JPEG 编码器");
        ImageWriter writer = writers.next();
        ImageOutputStream ios = null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ios = ImageIO.createImageOutputStream(bos);
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality((float) Math.max(0.1, Math.min(1.0, quality)));
            writer.write(null, new IIOImage(img, null, null), param);
            ios.flush();
            ios.close();
            ios = null;
            return bos.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("图片压缩失败，请换一张");
        } finally {
            if (ios != null) {
                try { ios.close(); } catch (IOException ignored) {}
            }
            writer.dispose();
        }
    }

    private static String detectFormat(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "JPEG";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "PNG";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "WEBP";
        }
        return null;
    }

    private Stored store(Compressed img) {
        LocalDate day = LocalDate.now(ZONE);
        String rel = String.format("%04d/%02d/%02d", day.getYear(), day.getMonthValue(), day.getDayOfMonth());
        Path dir = Paths.get(props.getUploadDir()).toAbsolutePath().normalize().resolve(rel);
        String uuid = UUID.randomUUID().toString().replace("-", "");
        Path full = dir.resolve(uuid + ".jpg");
        Path thumb = dir.resolve(uuid + "_thumb.jpg");
        try {
            Files.createDirectories(dir);
            Files.write(full, img.full());
            Files.write(thumb, img.thumb());
        } catch (IOException e) {
            log.error("风采图片落盘失败: {}", dir, e);
            throw new BusinessException("图片保存失败，请稍后重试");
        }
        String base = trimSlash(props.getUrlPrefix()) + "/" + rel + "/" + uuid;
        return new Stored(base + ".jpg", base + "_thumb.jpg", full, thumb);
    }

    private static void deleteQuietly(Path... paths) {
        for (Path p : paths) {
            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
        }
    }

    private static String trimSlash(String s) {
        String v = s == null ? "/upload/moments" : s;
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    // ==================== 通用 ====================

    private void checkEnabled() {
        if (!props.isEnabled()) throw new BusinessException("会员风采功能未开启");
    }

    private Member requireMember() {
        Member m = memberMapper.selectOne(new LambdaQueryWrapper<Member>()
                .eq(Member::getUserId, AuthContext.userId()).last("LIMIT 1"));
        if (m == null) throw new BusinessException("仅会员可发布风采图片");
        return m;
    }

    private Long myMemberIdOrNull() {
        Long uid = AuthContext.userId();
        if (uid == null) return null;
        Member m = memberMapper.selectOne(new LambdaQueryWrapper<Member>()
                .eq(Member::getUserId, uid).last("LIMIT 1"));
        return m == null ? null : m.getId();
    }

    private String displayName() {
        Long uid = AuthContext.userId();
        Member m = memberMapper.selectOne(new LambdaQueryWrapper<Member>()
                .eq(Member::getUserId, uid).last("LIMIT 1"));
        if (m != null) return m.getName();
        Coach c = coachMapper.selectOne(new LambdaQueryWrapper<Coach>()
                .eq(Coach::getUserId, uid).last("LIMIT 1"));
        return c == null ? "用户" : c.getName();
    }

    private MomentDtos.MediaVO toVO(Media m, Long myMemberId) {
        return new MomentDtos.MediaVO(m.getId(), m.getMemberId(), m.getAuthorName(), m.getTitle(),
                m.getUrl(), m.getThumbUrl(), m.getWidth() == null ? 0 : m.getWidth(),
                m.getHeight() == null ? 0 : m.getHeight(),
                m.getSourceSize() == null ? 0 : m.getSourceSize(),
                m.getOutputSize() == null ? 0 : m.getOutputSize(),
                m.getSourceFormat(), m.getBatchId(), m.getStatus(), m.getRejectReason(),
                m.getAuditStatus(), m.getAuditLabel(), m.getAuditScore(),
                m.getCreateTime() == null ? "" : m.getCreateTime().format(FMT),
                m.getCommentCount() == null ? 0 : m.getCommentCount(),
                myMemberId != null && myMemberId.equals(m.getMemberId()));
    }

    private MomentDtos.CommentVO toCommentVO(MediaComment c) {
        return new MomentDtos.CommentVO(c.getId(), c.getMediaId(), c.getUserId(), c.getAuthorName(),
                c.getAuthorRole(), c.getRootId(), c.getParentId(), c.getReplyToName(), c.getContent(),
                c.getStatus(), c.getCreateTime() == null ? "" : c.getCreateTime().format(FMT),
                AuthContext.userId() != null && AuthContext.userId().equals(c.getUserId()));
    }
}
