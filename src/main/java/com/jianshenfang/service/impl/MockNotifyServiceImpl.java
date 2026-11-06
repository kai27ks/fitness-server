package com.jianshenfang.service.impl;

import com.jianshenfang.entity.NotifyLog;
import com.jianshenfang.mapper.NotifyLogMapper;
import com.jianshenfang.service.NotifyService;
import com.jianshenfang.service.WechatTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * 通知实现：先保留原有 notify_log 记录，再把「预约成功 / 预约取消」异步转发到公众号模板消息。
 * 异步是为了微信接口挂掉或超时不影响预约主流程；发送结果单独记在 wechat_notify_log。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MockNotifyServiceImpl implements NotifyService {

    private final NotifyLogMapper notifyLogMapper;
    private final WechatTemplateService wechatTemplateService;

    @Override
    public void send(String scene, String receiver, Long bizId, String content) {
        try {
            NotifyLog record = new NotifyLog();
            record.setScene(scene);
            record.setReceiver(receiver);
            record.setBizId(bizId);
            record.setContent(content);
            record.setStatus("SUCCESS");
            notifyLogMapper.insert(record);
            log.info("[NOTIFY] scene={} receiver={} content={}", scene, receiver, content);
        } catch (Exception e) {
            log.error("通知记录写入失败 scene={} receiver={}", scene, receiver, e);
        }

        // Q9：只发会员侧两类；上课前提醒由 BookingReminderJob 单独触发
        if ("BOOKING_CREATED".equals(scene) || "BOOKING_CANCELED".equals(scene)) {
            CompletableFuture.runAsync(() -> {
                try {
                    wechatTemplateService.sendByPhone(scene, receiver, bizId, content);
                } catch (Exception e) {
                    log.warn("模板消息转发失败 scene={} bizId={}", scene, bizId, e);
                }
            });
        }
    }
}
