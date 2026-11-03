package com.jianshenfang.job;

import com.jianshenfang.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 到点自动签到任务：每分钟扫描已到开课时间的预约，签到并扣减课时。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoCheckInJob {

    private final BookingService bookingService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    public void run() {
        try {
            bookingService.autoCheckIn();
        } catch (Exception e) {
            log.error("自动签到任务执行失败", e);
        }
    }
}
