package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.auth.AuthContext;
import com.jianshenfang.dto.MemberDtos;
import com.jianshenfang.entity.Booking;
import com.jianshenfang.entity.Coach;
import com.jianshenfang.entity.CourseTemplate;
import com.jianshenfang.entity.MemberSessionLog;
import com.jianshenfang.mapper.BookingMapper;
import com.jianshenfang.mapper.CoachMapper;
import com.jianshenfang.mapper.CourseTemplateMapper;
import com.jianshenfang.mapper.MemberSessionLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 会员课包课时流水。所有改动 remain_sessions 的地方都应调用这里写一条流水，
 * 保证「谁在什么时候、因为什么原因、变动几节、变动后剩几节」可追溯。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionLogService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter FMT_FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_LIMIT = 200;

    private final MemberSessionLogMapper logMapper;
    private final CourseTemplateMapper courseMapper;
    private final CoachMapper coachMapper;
    private final BookingMapper bookingMapper;

    /**
     * 写一条课时流水。写失败只记日志，不影响主流程（与操作日志一致）。
     *
     * @param change 正数增加、负数扣减
     */
    public void record(Long memberId, Long packageId, Long bookingId, Long courseId, Long coachId,
                       String type, int change, Integer remainAfter, String remark) {
        if (memberId == null) return;
        try {
            MemberSessionLog l = new MemberSessionLog();
            l.setMemberId(memberId);
            l.setPackageId(packageId);
            l.setBookingId(bookingId);
            l.setCourseId(courseId);
            l.setCoachId(coachId);
            l.setType(type);
            l.setChangeSessions(change);
            l.setRemainAfter(remainAfter);
            l.setRemark(remark == null || remark.length() <= 200 ? remark : remark.substring(0, 197) + "...");
            l.setOperatorId(AuthContext.userId());
            l.setCreateTime(LocalDateTime.now());
            logMapper.insert(l);
        } catch (Exception e) {
            log.warn("课时流水写入失败 member={} package={} type={} change={}", memberId, packageId, type, change, e);
        }
    }

    /** 会员课时流水（可按课包过滤），按时间倒序 */
    public List<MemberDtos.SessionLogVO> list(Long memberId, Long packageId, Integer limit) {
        int size = limit == null ? 100 : Math.max(1, Math.min(limit, MAX_LIMIT));
        LambdaQueryWrapper<MemberSessionLog> w = new LambdaQueryWrapper<MemberSessionLog>()
                .eq(MemberSessionLog::getMemberId, memberId)
                .orderByDesc(MemberSessionLog::getCreateTime)
                .orderByDesc(MemberSessionLog::getId)
                .last("LIMIT " + size);
        if (packageId != null) w.eq(MemberSessionLog::getPackageId, packageId);
        return logMapper.selectList(w).stream().map(this::toVO).toList();
    }

    private MemberDtos.SessionLogVO toVO(MemberSessionLog l) {
        CourseTemplate course = l.getCourseId() == null ? null : courseMapper.selectById(l.getCourseId());
        Coach coach = l.getCoachId() == null ? null : coachMapper.selectById(l.getCoachId());
        String bookingTime = null;
        if (l.getBookingId() != null) {
            Booking b = bookingMapper.selectById(l.getBookingId());
            if (b != null && b.getStartTime() != null) bookingTime = b.getStartTime().format(FMT);
        }
        return new MemberDtos.SessionLogVO(
                l.getId(), l.getType(),
                l.getChangeSessions() == null ? 0 : l.getChangeSessions(),
                l.getRemainAfter(), l.getPackageId(), l.getMemberId(),
                course == null ? "课程" : course.getName(),
                coach == null ? null : coach.getName(),
                bookingTime, l.getRemark(),
                l.getCreateTime() == null ? null : l.getCreateTime().format(FMT_FULL));
    }
}
