package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.jianshenfang.config.WxProperties;
import com.jianshenfang.entity.*;
import com.jianshenfang.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公众号模板消息：预约成功 / 改约取消 / 上课前提醒（Q9）。
 * - 必须已关注服务号（未关注 43004），未关注只记 SKIPPED 不重试；
 * - dedup_key 保证同一事件只发一次；
 * - 发送与业务主流程解耦（由 MockNotifyServiceImpl 异步调用）。
 *
 * 注意：模板消息 data 的字段名（first/keyword1...）由公众号后台所选模板决定，
 * 若实际模板字段不同，只需修改本类 buildData() 一处。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatTemplateService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final WxProperties wx;
    private final WxApiClient wxApiClient;
    private final MemberMapper memberMapper;
    private final WechatAccountMapper accountMapper;
    private final BookingMapper bookingMapper;
    private final CourseTemplateMapper courseMapper;
    private final CoachMapper coachMapper;
    private final WechatNotifyLogMapper notifyLogMapper;

    /** 由 NotifyService 转发：receiver 为会员手机号时才发（COACH-x / ADMIN 跳过） */
    public void sendByPhone(String scene, String phone, Long bizId, String fallback) {
        if (!canSend(scene) || phone == null || !phone.matches("^1\\d{10}$")) return;
        Member member = memberMapper.selectOne(new LambdaQueryWrapper<Member>()
                .eq(Member::getPhone, phone));
        if (member == null) return;
        send(scene, member.getId(), bizId, null, fallback);
    }

    /** 定时任务调用：上课前 N 小时提醒 */
    public void sendReminder(Booking booking) {
        if (!canSend("BOOKING_REMINDER")) return;
        send("BOOKING_REMINDER", booking.getMemberId(), booking.getId(), booking, null);
    }

    private void send(String scene, Long memberId, Long bizId, Booking booking, String fallback) {
        Member member = memberMapper.selectById(memberId);
        if (member == null) return;
        WechatAccount account = accountMapper.selectOne(new LambdaQueryWrapper<WechatAccount>()
                .eq(WechatAccount::getUserId, member.getUserId()));
        String templateId = wx.getTemplates().get(scene);
        String dedupKey = scene + ":" + bizId + ":" + (account == null ? "NO_WX" : account.getOpenid());

        if (account == null) {
            skip(dedupKey, scene, null, memberId, bizId, templateId, "会员未绑定微信", fallback);
            return;
        }
        if (account.getSubscribed() == null || account.getSubscribed() != 1) {
            skip(dedupKey, scene, account.getOpenid(), memberId, bizId, templateId, "未关注服务号", fallback);
            return;
        }
        if (notifyLogMapper.selectCount(new LambdaQueryWrapper<WechatNotifyLog>()
                .eq(WechatNotifyLog::getDedupKey, dedupKey)) > 0) {
            return;
        }

        Booking b = booking != null ? booking : (bizId == null ? null : bookingMapper.selectById(bizId));
        Map<String, String> data = buildData(scene, b, fallback);
        String content = fallback == null ? data.get("remark") : fallback;

        if (wx.isMock()) {
            skip(dedupKey, scene, account.getOpenid(), memberId, bizId, templateId,
                    "dev mock 模式跳过真实发送", content);
            return;
        }

        JsonNode resp = wxApiClient.sendTemplate(account.getOpenid(), templateId,
                wx.getH5BaseUrl() + "/bookings", data);
        if (resp == null) {
            writeLog(dedupKey, scene, account.getOpenid(), memberId, bizId, templateId,
                    "FAILED", null, "本地调用异常", content);
            return;
        }
        int errcode = resp.path("errcode").asInt(-1);
        String errmsg = resp.path("errmsg").asText("");
        if (errcode == 0) {
            writeLogWithMsgid(dedupKey, scene, account.getOpenid(), memberId, bizId, templateId,
                    "SUCCESS", errcode, errmsg, content, resp.path("msgid").asText(""));
        } else if (errcode == 43004 || errcode == 40003) {
            // 业务前提不满足：不重试、不轰炸
            writeLog(dedupKey, scene, account.getOpenid(), memberId, bizId, templateId,
                    "SKIPPED", errcode, errmsg, content);
        } else {
            if (errcode == 40001) wxApiClient.clearToken();
            writeLog(dedupKey, scene, account.getOpenid(), memberId, bizId, templateId,
                    "FAILED", errcode, errmsg, content);
        }
    }

    private Map<String, String> buildData(String scene, Booking b, String fallback) {
        String course = "课程", time = "", coach = "教练";
        if (b != null) {
            CourseTemplate c = courseMapper.selectById(b.getCourseId());
            Coach co = coachMapper.selectById(b.getCoachId());
            course = c == null ? "课程" : c.getName();
            coach = co == null ? "教练" : co.getName();
            time = b.getStartTime() == null ? "" : b.getStartTime().format(FMT);
        }
        LinkedHashMap<String, String> data = new LinkedHashMap<>();
        data.put("first", title(scene));
        data.put("keyword1", course);
        data.put("keyword2", time);
        data.put("keyword3", coach);
        data.put("remark", fallback == null || fallback.isBlank() ? "请到「我的预约」查看课程详情。" : fallback);
        return data;
    }

    private String title(String scene) {
        return switch (scene) {
            case "BOOKING_CREATED" -> "预约成功通知";
            case "BOOKING_CANCELED" -> "预约取消通知";
            case "BOOKING_REMINDER" -> "上课提醒";
            default -> "课程通知";
        };
    }

    private boolean canSend(String scene) {
        if (!wx.isNotifyEnabled()) return false;
        String templateId = wx.getTemplates().get(scene);
        return templateId != null && !templateId.isBlank();
    }

    private void skip(String dedupKey, String scene, String openid, Long memberId, Long bizId,
                      String templateId, String reason, String content) {
        writeLog(dedupKey, scene, openid, memberId, bizId, templateId, "SKIPPED", null, reason, content);
    }

    private void writeLog(String dedupKey, String scene, String openid, Long memberId, Long bizId,
                          String templateId, String status, Integer errcode, String errmsg, String content) {
        writeLogWithMsgid(dedupKey, scene, openid, memberId, bizId, templateId,
                status, errcode, errmsg, content, null);
    }

    private void writeLogWithMsgid(String dedupKey, String scene, String openid, Long memberId, Long bizId,
                                   String templateId, String status, Integer errcode, String errmsg,
                                   String content, String msgid) {
        try {
            WechatNotifyLog record = new WechatNotifyLog();
            record.setDedupKey(dedupKey);
            record.setScene(scene);
            record.setOpenid(openid);
            record.setMemberId(memberId);
            record.setBizId(bizId);
            record.setTemplateId(templateId);
            record.setStatus(status);
            record.setErrcode(errcode);
            record.setErrmsg(errmsg == null ? null : (errmsg.length() > 200 ? errmsg.substring(0, 200) : errmsg));
            record.setMsgid(msgid);
            record.setRetryCount(0);
            record.setContent(content == null ? null : (content.length() > 500 ? content.substring(0, 500) : content));
            record.setCreateTime(LocalDateTime.now());
            notifyLogMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发下另一实例已写入，幂等生效
        } catch (Exception e) {
            log.warn("写通知日志失败 dedupKey={}", dedupKey, e);
        }
    }
}
