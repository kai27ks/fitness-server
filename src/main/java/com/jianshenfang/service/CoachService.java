package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.dto.BookingDtos;
import com.jianshenfang.dto.MemberDtos;
import com.jianshenfang.entity.*;
import com.jianshenfang.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CoachService {

    private final CoachMapper coachMapper;
    private final CoachQualificationMapper qualificationMapper;
    private final CoachLeaveMapper leaveMapper;
    private final CourseTemplateMapper courseMapper;
    private final BookingMapper bookingMapper;
    private final MemberMapper memberMapper;
    private final MemberPackageMapper packageMapper;

    @Value("${app.studio.open-hour:9}") private int openHour;
    @Value("${app.studio.close-hour:21}") private int closeHour;

    public List<MemberDtos.CoachBrief> list() {
        return coachMapper.selectList(new LambdaQueryWrapper<Coach>()
                        .eq(Coach::getStatus, 1).orderByAsc(Coach::getId))
                .stream().map(c -> new MemberDtos.CoachBrief(c.getId(), c.getName(), c.getPhoto(),
                        c.getIntro() == null ? "" : c.getIntro(), "已认证 · 1V1"))
                .toList();
    }

    public record CoachDetail(MemberDtos.CoachBrief coach, String intro,
                              List<MemberDtos.Qualification> qualifications, boolean onLeaveToday) {}

    public CoachDetail detail(Long coachId) {
        Coach coach = coachMapper.selectById(coachId);
        if (coach == null || coach.getStatus() != 1) {
            throw new BusinessException("教练不存在或已停用");
        }
        List<MemberDtos.Qualification> quals = qualificationMapper.selectList(
                        new LambdaQueryWrapper<CoachQualification>()
                                .eq(CoachQualification::getCoachId, coachId)
                                .orderByDesc(CoachQualification::getExpireDate))
                .stream().map(q -> new MemberDtos.Qualification(
                        q.getName(), q.getCertNo(), q.getIssuer(), q.getImage(),
                        q.getExpireDate() == null ? null : q.getExpireDate().toString()))
                .toList();

        boolean onLeaveToday = leaveMapper.selectCount(new LambdaQueryWrapper<CoachLeave>()
                .eq(CoachLeave::getCoachId, coachId)
                .le(CoachLeave::getStartTime, LocalDate.now().atTime(23, 59, 59))
                .ge(CoachLeave::getEndTime, LocalDate.now().atStartOfDay())) > 0;

        MemberDtos.CoachBrief brief = new MemberDtos.CoachBrief(
                coach.getId(), coach.getName(), coach.getPhoto(), coach.getIntro(), "已认证 · 1V1");
        return new CoachDetail(brief, coach.getIntro(), quals, onLeaveToday);
    }

    /**
     * 约课时段网格：教练上班（非请假）即可约，上课时间由会员预约决定。
     * 时段状态：FREE 可约 / BOOKED 已被他人约（置灰不可选）/ MINE 我的预约 / LEAVE 教练请假 / PAST 已过时间
     */
    public BookingDtos.SlotsResponse slots(Long coachId, String dateStr, Long courseId, Long userId) {
        Member member = memberMapper.selectOne(new LambdaQueryWrapper<Member>().eq(Member::getUserId, userId));
        return slotsForMember(coachId, dateStr, courseId, member);
    }

    /** 代约时使用已授权的目标会员，时段冲突与「会员已约」标记按该会员计算。 */
    public BookingDtos.SlotsResponse slotsForMember(Long coachId, String dateStr, Long courseId, Member member) {
        Coach coach = coachMapper.selectById(coachId);
        if (coach == null || coach.getStatus() != 1) {
            throw new BusinessException("教练不存在或已停用");
        }
        CourseTemplate course = courseMapper.selectById(courseId);
        if (course == null || course.getStatus() != 1) {
            throw new BusinessException("课程不存在或已下架");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(dateStr);
        } catch (Exception e) {
            throw new BusinessException("日期格式错误");
        }
        if (date.isBefore(LocalDate.now())) {
            throw new BusinessException("不能查询过去的日期");
        }

        Long memberId = member == null ? null : member.getId();

        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.atTime(LocalTime.MAX);

        List<CoachLeave> leaves = leaveMapper.selectList(new LambdaQueryWrapper<CoachLeave>()
                .eq(CoachLeave::getCoachId, coachId)
                .le(CoachLeave::getStartTime, dayEnd)
                .ge(CoachLeave::getEndTime, dayStart));

        List<Booking> bookings = bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                .and(q -> {
                    q.eq(Booking::getCoachId, coachId);
                    if (memberId != null) q.or().eq(Booking::getMemberId, memberId);
                })
                .in(Booking::getStatus, Booking.BOOKED, Booking.CHECKED_IN, Booking.COMPLETED)
                .lt(Booking::getStartTime, dayEnd)
                .gt(Booking::getEndTime, dayStart));

        LocalDateTime now = LocalDateTime.now();
        int duration = course.getDurationMin();
        if (duration <= 0 || duration > (closeHour - openHour) * 60) {
            throw new BusinessException("课程时长不符合营业时间要求");
        }
        List<BookingDtos.Slot> slots = new ArrayList<>();

        for (int minute = openHour * 60; minute + duration <= closeHour * 60; minute += duration) {
            LocalTime t = LocalTime.of(minute / 60, minute % 60);
            LocalDateTime start = date.atTime(t);
            LocalDateTime end = start.plusMinutes(duration);
            String status;
            if (!now.isBefore(start)) {
                status = "PAST";
            } else if (leaves.stream().anyMatch(l -> l.getStartTime().isBefore(end) && l.getEndTime().isAfter(start))) {
                status = "LEAVE";
            } else {
                Booking hit = bookings.stream()
                        .filter(b -> b.getStartTime().isBefore(end) && b.getEndTime().isAfter(start))
                        .findFirst().orElse(null);
                if (hit == null) {
                    status = "FREE";
                } else {
                    status = memberId != null && memberId.equals(hit.getMemberId()) ? "MINE" : "BOOKED";
                }
            }
            slots.add(new BookingDtos.Slot(t.toString(), end.toLocalTime().toString(), status));
        }

        int remain = 0;
        if (member != null) {
            remain = memberPackagesRemain(member.getId(), courseId);
        }
        return new BookingDtos.SlotsResponse(dateStr, duration, remain, slots);
    }

    private int memberPackagesRemain(Long memberId, Long courseId) {
        List<MemberPackage> list = packageMapper.selectList(new LambdaQueryWrapper<MemberPackage>()
                .eq(MemberPackage::getMemberId, memberId)
                .eq(MemberPackage::getCourseId, courseId)
                .eq(MemberPackage::getStatus, "ACTIVE")
                .ge(MemberPackage::getExpireDate, LocalDate.now()));
        return list.stream().mapToInt(MemberPackage::getRemainSessions).sum();
    }
}
