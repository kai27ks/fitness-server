package com.jianshenfang.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.config.WxProperties;
import com.jianshenfang.entity.Booking;
import com.jianshenfang.mapper.BookingMapper;
import com.jianshenfang.service.WechatTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 上课前提醒（Q11：提前 24h，只发一次）。
 * 每 10 分钟扫描 [now+24h-60min, now+24h+5min] 内的 BOOKED 预约，
 * 扫描窗口重叠由 wechat_notify_log 的 dedup_key 兜底，不会重复发送；
 * 已取消/请假的预约状态不再是 BOOKED，自然不会被扫到。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingReminderJob {

    private final WxProperties wx;
    private final BookingMapper bookingMapper;
    private final WechatTemplateService wechatTemplateService;

    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void run() {
        String templateId = wx.getTemplates().get("BOOKING_REMINDER");
        if (!wx.isNotifyEnabled() || templateId == null || templateId.isBlank()) return;
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime to = now.plusHours(wx.getReminderHours()).plusMinutes(5);
            LocalDateTime from = to.minusMinutes(wx.getReminderWindowMinutes() + 5);
            List<Booking> list = bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                    .eq(Booking::getStatus, Booking.BOOKED)
                    .between(Booking::getStartTime, from, to));
            for (Booking b : list) {
                wechatTemplateService.sendReminder(b);
            }
        } catch (Exception e) {
            log.error("上课前提醒任务执行失败", e);
        }
    }
}
