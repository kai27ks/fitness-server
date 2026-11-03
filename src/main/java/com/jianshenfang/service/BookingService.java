package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.dto.AdminDtos;
import com.jianshenfang.dto.BookingDtos;
import com.jianshenfang.entity.*;
import com.jianshenfang.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 约课核心业务。
 * 规则：
 * 1. 会员 1V1 绑定教练，仅可约专属教练的课（改绑由管理员操作）；
 * 2. 教练无固定排班，当天上班（非请假）且会员买了课即可约，时段冲突前置校验 + 唯一索引兜底；
 * 3. 预约后不可修改；会员开课前 2 小时可取消，教练可随时取消，签到后不可取消；
 * 4. 到点自动签到并扣减 1 节课时（CAS 幂等）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final List<String> OCCUPIED = List.of(Booking.BOOKED, Booking.CHECKED_IN, Booking.COMPLETED);

    private final BookingMapper bookingMapper;
    private final MemberMapper memberMapper;
    private final CoachMapper coachMapper;
    private final CoachLeaveMapper leaveMapper;
    private final CourseTemplateMapper courseMapper;
    private final MemberPackageMapper packageMapper;
    private final MemberService memberService;
    private final NotifyService notifyService;

    @Value("${app.studio.open-hour:9}") private int openHour;
    @Value("${app.studio.close-hour:21}") private int closeHour;

    public BookingDtos.CreateSuccess create(Long userId, BookingDtos.CreateRequest req) {
        Member member = memberService.currentMember(userId);

        MemberPackage pkg = packageMapper.selectById(req.packageId());
        if (pkg == null || !pkg.getMemberId().equals(member.getId())) {
            throw new BusinessException("课包不存在");
        }
        if (!"ACTIVE".equals(pkg.getStatus()) || pkg.getExpireDate().isBefore(LocalDate.now())) {
            throw new BusinessException("课包已过期，请续课后再约");
        }
        if (!pkg.getCourseId().equals(req.courseId())) {
            throw new BusinessException("课包与所选课程不匹配");
        }
        if (pkg.getRemainSessions() <= 0) {
            throw new BusinessException("剩余课时不足，请先续课");
        }
        CourseTemplate course = courseMapper.selectById(req.courseId());
        if (course == null || course.getStatus() != 1) {
            throw new BusinessException("课程不存在或已下架");
        }

        // 1V1 约束：会员绑定的专属教练
        if (member.getCoachId() == null) {
            throw new BusinessException("暂未绑定专属教练，请联系前台");
        }
        Coach coach = coachMapper.selectById(member.getCoachId());
        if (coach == null || coach.getStatus() != 1) {
            throw new BusinessException("专属教练已停用，请联系前台改绑");
        }

        LocalDateTime start;
        try {
            start = LocalDate.parse(req.date()).atTime(LocalTime.parse(req.startTime()));
        } catch (Exception e) {
            throw new BusinessException("日期或时间格式错误");
        }
        LocalDateTime end = start.plusMinutes(course.getDurationMin());

        if (!start.isAfter(LocalDateTime.now())) {
            throw new BusinessException("请选择晚于当前时间的时段");
        }
        LocalTime close = LocalTime.of(closeHour, 0);
        boolean crossDay = !end.toLocalTime().isAfter(start.toLocalTime()); // 跨天即超出营业时间
        if (start.getHour() < openHour || crossDay || end.toLocalTime().isAfter(close)) {
            throw new BusinessException("超出营业时间（" + openHour + ":00 - " + closeHour + ":00）");
        }

        // 教练该时段请假
        Long leave = leaveMapper.selectCount(new LambdaQueryWrapper<CoachLeave>()
                .eq(CoachLeave::getCoachId, coach.getId())
                .lt(CoachLeave::getStartTime, end)
                .gt(CoachLeave::getEndTime, start));
        if (leave > 0) {
            throw new BusinessException("教练该时段请假，请选择其他时间");
        }

        // 教练时段冲突（唯一索引兜底防超约）
        Long coachConflict = bookingMapper.selectCount(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getCoachId, coach.getId())
                .in(Booking::getStatus, OCCUPIED)
                .lt(Booking::getStartTime, end)
                .gt(Booking::getEndTime, start));
        if (coachConflict > 0) {
            throw new BusinessException("手慢了，该时段刚被预约");
        }

        // 会员自身时段冲突
        Long memberConflict = bookingMapper.selectCount(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getMemberId, member.getId())
                .in(Booking::getStatus, OCCUPIED)
                .lt(Booking::getStartTime, end)
                .gt(Booking::getEndTime, start));
        if (memberConflict > 0) {
            throw new BusinessException("您该时段已有课程安排");
        }

        Booking booking = new Booking();
        booking.setMemberId(member.getId());
        booking.setCoachId(coach.getId());
        booking.setCourseId(course.getId());
        booking.setPackageId(pkg.getId());
        booking.setStartTime(start);
        booking.setEndTime(end);
        booking.setStatus(Booking.BOOKED);
        try {
            bookingMapper.insert(booking);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("手慢了，该时段刚被预约");
        }

        notifyService.send("BOOKING_CREATED", member.getPhone(), booking.getId(),
                "预约成功：" + course.getName() + " " + start.format(FMT) + "，教练 " + coach.getName()
                        + "。开课将自动签到并扣减 1 节课时。");
        notifyService.send("BOOKING_CREATED", "COACH-" + coach.getId(), booking.getId(),
                "新预约：会员 " + member.getName() + " 预约 " + course.getName() + " " + start.format(FMT) + "。");

        return new BookingDtos.CreateSuccess(booking.getId(), pkg.getRemainSessions());
    }

    public void cancel(Long userId, Long bookingId, String reason) {
        Member member = memberService.currentMember(userId);
        Booking booking = bookingMapper.selectById(bookingId);
        if (booking == null || !booking.getMemberId().equals(member.getId())) {
            throw new BusinessException("预约记录不存在");
        }
        if (!Booking.BOOKED.equals(booking.getStatus())) {
            throw new BusinessException("当前状态不可取消（签到后不可取消）");
        }
        // 会员取消：距开课须 ≥ 2 小时（教练/管理端取消后续开放，规则为随时）
        if (booking.getStartTime().minusHours(2).isBefore(LocalDateTime.now())) {
            throw new BusinessException("距开课不足 2 小时，不可取消");
        }
        int rows = bookingMapper.casCancel(bookingId, "MEMBER", reason);
        if (rows == 0) {
            throw new BusinessException("取消失败，请刷新后重试");
        }

        CourseTemplate course = courseMapper.selectById(booking.getCourseId());
        Coach coach = coachMapper.selectById(booking.getCoachId());
        String courseName = course == null ? "课程" : course.getName();
        String coachName = coach == null ? "教练" : coach.getName();
        notifyService.send("BOOKING_CANCELED", member.getPhone(), bookingId,
                "已取消预约：" + courseName + " " + booking.getStartTime().format(FMT) + "（教练 " + coachName + "），未扣减课时。");
        notifyService.send("BOOKING_CANCELED", "COACH-" + booking.getCoachId(), bookingId,
                "会员 " + member.getName() + " 取消了 " + booking.getStartTime().format(FMT) + " 的课程。");
    }

    public List<BookingDtos.BookingVO> myBookings(Long userId, String tab) {
        Member member = memberService.currentMember(userId);
        LambdaQueryWrapper<Booking> wrapper = new LambdaQueryWrapper<Booking>()
                .eq(Booking::getMemberId, member.getId())
                .orderByDesc(Booking::getStartTime);
        if ("WAIT".equals(tab)) {
            wrapper.eq(Booking::getStatus, Booking.BOOKED);
        } else if ("DONE".equals(tab)) {
            wrapper.in(Booking::getStatus, Booking.CHECKED_IN, Booking.COMPLETED);
        }
        return bookingMapper.selectList(wrapper).stream()
                .map(b -> toVO(b, member.getId()))
                .toList();
    }

    /** 管理端/教练取消：不受 2 小时限制，随时可取消 */
    public void staffCancel(Long operatorId, Long bookingId, String reason) {
        Booking booking = bookingMapper.selectById(bookingId);
        if (booking == null) {
            throw new BusinessException("预约记录不存在");
        }
        if (!Booking.BOOKED.equals(booking.getStatus())) {
            throw new BusinessException("当前状态不可取消（签到后不可取消）");
        }
        int rows = bookingMapper.casCancel(bookingId, "STAFF", reason);
        if (rows == 0) {
            throw new BusinessException("取消失败，请刷新后重试");
        }
        CourseTemplate course = courseMapper.selectById(booking.getCourseId());
        Coach coach = coachMapper.selectById(booking.getCoachId());
        Member member = memberMapper.selectById(booking.getMemberId());
        String courseName = course == null ? "课程" : course.getName();
        String coachName = coach == null ? "教练" : coach.getName();
        String memberName = member == null ? "会员" : member.getName();
        notifyService.send("BOOKING_CANCELED", member == null ? "" : member.getPhone(), bookingId,
                "课程已取消：" + courseName + " " + booking.getStartTime().format(FMT) + "（教练 " + coachName + "），未扣减课时。");
        notifyService.send("BOOKING_CANCELED", "COACH-" + booking.getCoachId(), bookingId,
                memberName + " 的课程（" + booking.getStartTime().format(FMT) + "）已被管理员取消。");
        notifyService.send("BOOKING_CANCELED", "ADMIN", bookingId, "已代取消预约 " + bookingId + "（" + memberName + "）。");
    }

    /** 手动补签：管理端"打卡"入口；同样扣减 1 节课时 */
    public void manualCheckIn(Long bookingId) {
        Booking booking = bookingMapper.selectById(bookingId);
        if (booking == null) {
            throw new BusinessException("预约记录不存在");
        }
        if (!Booking.BOOKED.equals(booking.getStatus())) {
            throw new BusinessException("仅待上课的预约可补签");
        }
        int updated = bookingMapper.casCheckIn(bookingId);
        if (updated == 0) {
            throw new BusinessException("补签失败，请刷新后重试");
        }
        int deducted = packageMapper.deductOne(booking.getPackageId());
        if (deducted == 0) {
            notifyService.send("PACKAGE_ABNORMAL", "ADMIN", bookingId,
                    "课时异常：预约 " + bookingId + " 已补签但课包剩余不足，请人工核对。");
        } else {
            MemberPackage pkg = packageMapper.selectById(booking.getPackageId());
            Member member = memberMapper.selectById(booking.getMemberId());
            if (member != null) {
                notifyService.send("AUTO_CHECKIN", member.getPhone(), bookingId,
                        "已补签：" + booking.getStartTime().format(FMT) + "，扣减 1 节课时，剩余 " + pkg.getRemainSessions() + " 节。");
            }
        }
    }

    /** 管理端约课列表：按条件筛选（教练仅看名下） */
    public List<BookingDtos.AdminBookingVO> adminList(Long coachId, AdminDtos.BookingQuery q) {
        if (q == null) return List.of();
        LambdaQueryWrapper<Booking> wrapper = new LambdaQueryWrapper<Booking>().orderByDesc(Booking::getStartTime);
        if (coachId != null) wrapper.eq(Booking::getCoachId, coachId);
        if (q.coachId() != null) wrapper.eq(Booking::getCoachId, q.coachId());
        if (q.memberId() != null) wrapper.eq(Booking::getMemberId, q.memberId());
        if (q.status() != null && !q.status().isBlank()) wrapper.eq(Booking::getStatus, q.status());
        if (q.date() != null && !q.date().isBlank()) {
            wrapper.ge(Booking::getStartTime, q.date() + "T00:00:00").lt(Booking::getStartTime, q.date() + "T23:59:59");
        }
        if (q.startDate() != null) wrapper.ge(Booking::getStartTime, q.startDate() + "T00:00:00");
        if (q.endDate() != null) wrapper.le(Booking::getStartTime, q.endDate() + "T23:59:59");
        return bookingMapper.selectList(wrapper).stream().map(b -> {
            Member m = memberMapper.selectById(b.getMemberId());
            CourseTemplate c = courseMapper.selectById(b.getCourseId());
            Coach co = coachMapper.selectById(b.getCoachId());
            return new BookingDtos.AdminBookingVO(b.getId(),
                    m == null ? "会员" : m.getName(),
                    c == null ? "课程" : c.getName(),
                    co == null ? "教练" : co.getName(),
                    b.getStartTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    b.getEndTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    b.getStatus(), b.getCancelBy(), b.getCancelReason());
        }).toList();
    }

    private BookingDtos.BookingVO toVO(Booking b, Long memberId) {
        CourseTemplate course = courseMapper.selectById(b.getCourseId());
        Coach coach = coachMapper.selectById(b.getCoachId());
        LocalDateTime now = LocalDateTime.now();
        boolean cancellable = Booking.BOOKED.equals(b.getStatus())
                && memberId.equals(b.getMemberId())
                && b.getStartTime().minusHours(2).isAfter(now);
        return new BookingDtos.BookingVO(b.getId(),
                course == null ? "课程" : course.getName(),
                coach == null ? "教练" : coach.getName(),
                b.getStartTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                b.getEndTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                b.getStatus(), b.getCancelBy(), b.getCancelReason(), cancellable);
    }

    /**
     * 到点自动签到（仅会员课；教练默认在场不设签到）。
     * CAS 状态流转保证幂等：定时任务重复执行不会重复扣课。
     */
    public void autoCheckIn() {
        List<Booking> due = bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getStatus, Booking.BOOKED)
                .le(Booking::getStartTime, LocalDateTime.now()));
        for (Booking b : due) {
            try {
                int updated = bookingMapper.casCheckIn(b.getId());
                if (updated == 0) continue;

                int deducted = packageMapper.deductOne(b.getPackageId());
                if (deducted == 0) {
                    log.warn("课包课时异常 booking={} package={}", b.getId(), b.getPackageId());
                    notifyService.send("PACKAGE_ABNORMAL", "ADMIN", b.getId(),
                            "课时异常：预约 " + b.getId() + " 已签到但课包剩余不足，请人工核对。");
                } else {
                    MemberPackage pkg = packageMapper.selectById(b.getPackageId());
                    Member member = memberMapper.selectById(b.getMemberId());
                    CourseTemplate course = courseMapper.selectById(b.getCourseId());
                    String courseName = course == null ? "课程" : course.getName();
                    if (member != null) {
                        notifyService.send("AUTO_CHECKIN", member.getPhone(), b.getId(),
                                "已自动签到：" + courseName + " " + b.getStartTime().format(FMT)
                                        + "，扣减 1 节课时，剩余 " + pkg.getRemainSessions() + " 节。");
                    }
                }
            } catch (Exception e) {
                log.error("自动签到失败 booking={}", b.getId(), e);
            }
        }

        // 课程结束 -> 已完成
        List<Booking> finished = bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getStatus, Booking.CHECKED_IN)
                .le(Booking::getEndTime, LocalDateTime.now()));
        for (Booking b : finished) {
            try {
                bookingMapper.casComplete(b.getId());
            } catch (Exception e) {
                log.error("完结失败 booking={}", b.getId(), e);
            }
        }
    }
}
