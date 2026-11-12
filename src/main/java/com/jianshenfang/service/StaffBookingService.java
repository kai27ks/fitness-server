package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.auth.AuthContext;
import com.jianshenfang.auth.AuthUtil;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.dto.BookingDtos;
import com.jianshenfang.entity.*;
import com.jianshenfang.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** 教练仅代约名下会员；管理员代约会员当前绑定的教练。 */
@Service
@RequiredArgsConstructor
public class StaffBookingService {
    private final MemberMapper memberMapper;
    private final CoachMapper coachMapper;
    private final MemberPackageMapper packageMapper;
    private final CourseTemplateMapper courseMapper;
    private final BookingMapper bookingMapper;
    private final BookingService bookingService;
    private final CoachService coachService;
    private final OperationLogService logService;

    public List<BookingDtos.BookingMember> members(String keyword) {
        Long coachId = actorCoachId();
        LambdaQueryWrapper<Member> query = new LambdaQueryWrapper<Member>().orderByAsc(Member::getName);
        if (coachId != null) query.eq(Member::getCoachId, coachId);
        if (keyword != null && !keyword.isBlank()) {
            String key = keyword.strip();
            query.and(q -> q.like(Member::getName, key).or().like(Member::getPhone, key));
        }
        return memberMapper.selectList(query).stream().map(this::memberView).toList();
    }

    public BookingDtos.StaffOptions options(Long memberId) {
        Member member = requireMember(memberId, actorCoachId());
        List<BookingDtos.PackageOption> packages = packageMapper.selectList(new LambdaQueryWrapper<MemberPackage>()
                .eq(MemberPackage::getMemberId, memberId).orderByDesc(MemberPackage::getRemainSessions))
                .stream().map(p -> {
                    CourseTemplate course = courseMapper.selectById(p.getCourseId());
                    return new BookingDtos.PackageOption(p.getId(), p.getCourseId(),
                            course == null ? "已下架课程" : course.getName(), course == null ? 0 : course.getDurationMin(),
                            p.getRemainSessions(), p.getStartDate() == null ? null : p.getStartDate().toString(),
                            p.getExpireDate() == null ? null : p.getExpireDate().toString(), p.getStatus(),
                            course != null && Integer.valueOf(1).equals(course.getStatus()));
                }).toList();
        return new BookingDtos.StaffOptions(memberView(member), packages);
    }

    public BookingDtos.SlotsResponse slots(Long memberId, Long packageId, String date) {
        Member member = requireMember(memberId, actorCoachId());
        MemberPackage pkg = requirePackage(memberId, packageId);
        LocalDate selected;
        try { selected = LocalDate.parse(date); }
        catch (Exception e) { throw new BusinessException("日期格式错误"); }
        if (!"ACTIVE".equals(pkg.getStatus()) || pkg.getRemainSessions() <= 0) {
            throw new BusinessException("该课包已不可用，请选择其他课包");
        }
        if ((pkg.getStartDate() != null && selected.isBefore(pkg.getStartDate()))
                || (pkg.getExpireDate() != null && selected.isAfter(pkg.getExpireDate()))) {
            throw new BusinessException("所选日期不在课包有效期内");
        }
        if (member.getCoachId() == null) throw new BusinessException("会员未绑定教练，请先联系管理员设置");
        return coachService.slotsForMember(member.getCoachId(), date, pkg.getCourseId(), member);
    }

    @Transactional
    public BookingDtos.CreateSuccess create(BookingDtos.StaffCreateRequest req) {
        Long coachId = actorCoachId();
        Member member = requireMember(req.memberId(), coachId);
        MemberPackage pkg = requirePackage(member.getId(), req.packageId());
        BookingDtos.CreateSuccess result = bookingService.createForMember(member.getId(), coachId,
                new BookingDtos.CreateRequest(pkg.getId(), pkg.getCourseId(), req.date(), req.startTime()));
        logService.log("STAFF_CREATE_BOOKING", "BOOKING", result.bookingId(), "代会员 ID=" + member.getId() + " 约课");
        return result;
    }

    /** 取消、补签同样检查预约所属教练，不能通过伪造预约 ID 操作他人记录。 */
    public void requireBookingAccess(Long bookingId) {
        Long coachId = actorCoachId();
        Booking booking = bookingMapper.selectById(bookingId);
        if (booking == null) throw new BusinessException("预约记录不存在");
        if (coachId != null && !coachId.equals(booking.getCoachId())) {
            throw new BusinessException(403, "只能操作本人授课的预约");
        }
    }

    private Long actorCoachId() {
        AuthUtil.requireStaff();
        if ("ADMIN".equals(AuthContext.role())) return null;
        Coach coach = coachMapper.selectOne(new LambdaQueryWrapper<Coach>().eq(Coach::getUserId, AuthContext.userId()));
        if (coach == null) throw new BusinessException("当前账号尚未关联教练档案，请联系管理员");
        return coach.getId();
    }

    private Member requireMember(Long memberId, Long coachId) {
        Member member = memberMapper.selectById(memberId);
        if (member == null) throw new BusinessException("会员不存在");
        if (coachId != null && !coachId.equals(member.getCoachId())) {
            throw new BusinessException(403, "只能为本人名下会员约课");
        }
        return member;
    }

    private MemberPackage requirePackage(Long memberId, Long packageId) {
        MemberPackage pkg = packageMapper.selectById(packageId);
        if (pkg == null || !memberId.equals(pkg.getMemberId())) throw new BusinessException("该课包不属于所选会员");
        return pkg;
    }

    private BookingDtos.BookingMember memberView(Member member) {
        Coach coach = member.getCoachId() == null ? null : coachMapper.selectById(member.getCoachId());
        return new BookingDtos.BookingMember(member.getId(), member.getName(), member.getPhone(),
                member.getCoachId(), coach == null ? null : coach.getName());
    }
}
