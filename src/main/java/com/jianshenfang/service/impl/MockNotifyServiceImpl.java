package com.jianshenfang.service.impl;

import com.jianshenfang.entity.NotifyLog;
import com.jianshenfang.mapper.NotifyLogMapper;
import com.jianshenfang.service.NotifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 通知 Mock 实现：只写 notify_log，模拟公众号模板消息发送成功。
 * 发送失败不影响主流程（捕获所有异常）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MockNotifyServiceImpl implements NotifyService {

    private final NotifyLogMapper notifyLogMapper;

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
            log.error("通知发送失败 scene={} receiver={}", scene, receiver, e);
        }
    }
}
