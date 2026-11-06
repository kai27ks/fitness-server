package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.dto.MemberDtos;
import com.jianshenfang.entity.*;
import com.jianshenfang.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberMapper memberMapper;
    private final CoachMapper coachMapper;
    private final CoachLeaveMapper leaveMapper;
    private final CourseTemplateMapper courseMapper;
    private final MemberPackageMapper packageMapper;
    private final BookingMapper bookingMapper;
    private final WechatAccountMapper wechatAccountMapper;

    @Value("${app.studio.name}") private String studioName;
    @Value("${app.studio.address}") private String studioAddress;
    @Value("${app.studio.hours}") private String studioHours;

    /** 当前登录用户对应的会员档案（非会员账号拒绝） */
    public Member currentMember(Long userId) {
        Member member = memberMapper.selectOne(new LambdaQueryWrapper<Member>().eq(Member::getUserId, userId));
        if (member == null) {
            throw new BusinessException("请使用会员账号登录");
        }
        return member;
    }

    public MemberDtos.HomeResponse home(Long userId) {
        Member member = currentMember(userId);

        MemberDtos.CoachBrief coach = null;
        boolean onLeaveToday = false;
        if (member.getCoachId() != null) {
            coach = toBrief(coachMapper.selectById(member.getCoachId()));
            onLeaveToday = leaveMapper.selectCount(new LambdaQueryWrapper<CoachLeave>()
                    .eq(CoachLeave::getCoachId, member.getCoachId())
                    .le(CoachLeave::getStartTime, LocalDate.now().atTime(23, 59, 59))
                    .ge(CoachLeave::getEndTime, LocalDate.now().atStartOfDay())) > 0;
        }

        MemberDtos.PackageInfo active = bestPackage(member.getId());

        List<MemberDtos.CoachBrief> coaches = coachMapper.selectList(
                        new LambdaQueryWrapper<Coach>().eq(Coach::getStatus, 1).orderByAsc(Coach::getId))
                .stream().map(this::toBrief).toList();

        return new MemberDtos.HomeResponse(member.getName(), coach, onLeaveToday, active, coaches,
                new MemberDtos.Studio(studioName, studioAddress, studioHours));
    }

    private MemberDtos.CoachBrief toBrief(Coach coach) {
        if (coach == null) return null;
        String intro = coach.getIntro() == null ? "" : coach.getIntro();
        String brief = intro.length() > 24 ? intro.substring(0, 24) + "…" : intro;
        return new MemberDtos.CoachBrief(coach.getId(), coach.getName(), coach.getPhoto(), brief, "已认证 · 1V1");
    }

    /** 剩余课时最多且未过期的课包 */
    private MemberDtos.PackageInfo bestPackage(Long memberId) {
        List<MemberPackage> list = packageMapper.selectList(new LambdaQueryWrapper<MemberPackage>()
                .eq(MemberPackage::getMemberId, memberId)
                .eq(MemberPackage::getStatus, "ACTIVE")
                .ge(MemberPackage::getExpireDate, LocalDate.now())
                .orderByDesc(MemberPackage::getRemainSessions));
        return list.isEmpty() ? null : toInfo(list.get(0));
    }

    private MemberDtos.PackageInfo toInfo(MemberPackage p) {
        CourseTemplate course = courseMapper.selectById(p.getCourseId());
        return new MemberDtos.PackageInfo(p.getId(), p.getCourseId(),
                course == null ? "课程" : course.getName(),
                p.getRemainSessions(), p.getTotalSessions(),
                p.getExpireDate().toString(), p.getStatus(), p.getTotalPrice().toPlainString());
    }

    public List<MemberDtos.PackageInfo> myPackages(Long userId) {
        Member member = currentMember(userId);
        return packageMapper.selectList(new LambdaQueryWrapper<MemberPackage>()
                        .eq(MemberPackage::getMemberId, member.getId())
                        .orderByDesc(MemberPackage::getRemainSessions))
                .stream().map(this::toInfo).toList();
    }

    public MemberDtos.MeResponse me(Long userId) {
        Member member = currentMember(userId);
        Coach coach = member.getCoachId() == null ? null : coachMapper.selectById(member.getCoachId());

        int remain = packageMapper.selectList(new LambdaQueryWrapper<MemberPackage>()
                        .eq(MemberPackage::getMemberId, member.getId()).eq(MemberPackage::getStatus, "ACTIVE"))
                .stream().mapToInt(MemberPackage::getRemainSessions).sum();

        int consumed = Math.toIntExact(bookingMapper.selectCount(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getMemberId, member.getId())
                .in(Booking::getStatus, Booking.CHECKED_IN, Booking.COMPLETED)));

        int upcoming = Math.toIntExact(bookingMapper.selectCount(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getMemberId, member.getId())
                .eq(Booking::getStatus, Booking.BOOKED)
                .gt(Booking::getStartTime, LocalDateTime.now())));

        int packageCount = Math.toIntExact(packageMapper.selectCount(new LambdaQueryWrapper<MemberPackage>()
                .eq(MemberPackage::getMemberId, member.getId())));

        String phone = member.getPhone();
        String masked = phone.length() == 11 ? phone.substring(0, 3) + "****" + phone.substring(7) : phone;
        WechatAccount wa = wechatAccountMapper.selectOne(new LambdaQueryWrapper<WechatAccount>()
                .eq(WechatAccount::getUserId, userId));
        String avatarUrl = wa == null ? null : wa.getAvatarUrl();
        return new MemberDtos.MeResponse(member.getName(), masked, coach == null ? null : coach.getName(),
                avatarUrl, remain, consumed, upcoming, packageCount);
    }
}
