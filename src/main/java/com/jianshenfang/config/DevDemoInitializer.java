package com.jianshenfang.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.entity.Booking;
import com.jianshenfang.entity.CoachLeave;
import com.jianshenfang.entity.SysUser;
import com.jianshenfang.mapper.BookingMapper;
import com.jianshenfang.mapper.CoachLeaveMapper;
import com.jianshenfang.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 仅 dev 环境生效：
 * 1. 把种子数据里的占位密码改为 123456（BCrypt 无法在 SQL 里预生成）；
 * 2. 每天补一条"今天"的演示预约/请假，保证约课页能看到 已被约/我的预约/请假 状态。
 */
@Slf4j
@Profile("dev")
@Component
@RequiredArgsConstructor
public class DevDemoInitializer implements ApplicationRunner {

    private final SysUserMapper userMapper;
    private final BookingMapper bookingMapper;
    private final CoachLeaveMapper leaveMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public void run(ApplicationArguments args) {
        for (long id = 1; id <= 4; id++) {
            SysUser user = userMapper.selectById(id);
            if (user != null && !user.getPassword().startsWith("$2")) {
                SysUser update = new SysUser();
                update.setId(id);
                update.setPassword(encoder.encode("123456"));
                userMapper.updateById(update);
                log.info("演示账号 {} 密码已重置为 123456", user.getPhone());
            }
        }

        LocalDateTime slot1 = LocalDate.now().atTime(15, 0);
        Long exists1 = bookingMapper.selectCount(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getCoachId, 1L).eq(Booking::getStartTime, slot1));
        if (exists1 == 0) {
            Booking b = new Booking();
            b.setMemberId(2L);
            b.setCoachId(1L);
            b.setCourseId(1L);
            b.setPackageId(3L);
            b.setStartTime(slot1);
            b.setEndTime(slot1.plusMinutes(60));
            b.setStatus(Booking.BOOKED);
            try {
                bookingMapper.insert(b);
            } catch (Exception ignored) {
            }
        }

        LocalDateTime leaveStart = LocalDate.now().atTime(19, 0);
        Long existsLeave = leaveMapper.selectCount(new LambdaQueryWrapper<CoachLeave>()
                .eq(CoachLeave::getCoachId, 1L).eq(CoachLeave::getStartTime, leaveStart));
        if (existsLeave == 0) {
            CoachLeave leave = new CoachLeave();
            leave.setCoachId(1L);
            leave.setStartTime(leaveStart);
            leave.setEndTime(LocalDate.now().atTime(21, 0));
            leave.setReason("临时私事");
            leave.setStatus(1);
            try {
                leaveMapper.insert(leave);
            } catch (Exception ignored) {
            }
        }
    }
}
