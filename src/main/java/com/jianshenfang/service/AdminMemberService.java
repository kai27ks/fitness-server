package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.dto.AdminDtos;
import com.jianshenfang.entity.*;
import com.jianshenfang.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 会员管理（管理端）：新增/查询/改绑教练/课包增购与课时调整。
 * 管理员代录会员时一并创建登录账号（默认密码 123456）。
 */
@Service
@RequiredArgsConstructor
public class AdminMemberService {

    private final MemberMapper memberMapper;
    private final SysUserMapper userMapper;
    private final CoachMapper coachMapper;
    private final CourseTemplateMapper courseMapper;
    private final MemberPackageMapper packageMapper;
    private final BookingMapper bookingMapper;
    private final OperationLogService logService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public List<Member> list(String keyword, Long coachId) {
        LambdaQueryWrapper<Member> w = new LambdaQueryWrapper<Member>().orderByDesc(Member::getId);
        if (keyword != null && !keyword.isBlank()) {
            w.and(x -> x.like(Member::getName, keyword).or().like(Member::getPhone, keyword));
        }
        if (coachId != null) w.eq(Member::getCoachId, coachId);
        return memberMapper.selectList(w);
    }

    public Member create(AdminDtos.MemberCreate req) {
        if (memberMapper.selectCount(new LambdaQueryWrapper<Member>().eq(Member::getPhone, req.phone())) > 0) {
            throw new BusinessException("该手机号会员已存在");
        }
        if (req.coachId() != null) {
            Coach coach = coachMapper.selectById(req.coachId());
            if (coach == null || coach.getStatus() != 1) {
                throw new BusinessException("绑定教练不存在或已停用");
            }
        }
        SysUser user = new SysUser();
        user.setPhone(req.phone());
        user.setPassword(encoder.encode(req.password() == null ? "123456" : req.password()));
        user.setRole("MEMBER");
        user.setStatus(1);
        userMapper.insert(user);

        Member m = new Member();
        m.setUserId(user.getId());
        m.setName(req.name());
        m.setPhone(req.phone());
        m.setGender(req.gender());
        m.setBirthday(req.birthday() == null ? null : LocalDate.parse(req.birthday()));
        m.setCoachId(req.coachId());
        memberMapper.insert(m);
        logService.log("CREATE_MEMBER", "MEMBER", m.getId(), "管理员代录会员 " + req.phone());
        return m;
    }

    public MemberDetailVO detail(Long memberId) {
        Member member = memberMapper.selectById(memberId);
        if (member == null) throw new BusinessException("会员不存在");
        Coach coach = member.getCoachId() == null ? null : coachMapper.selectById(member.getCoachId());
        List<MemberPackage> packages = packageMapper.selectList(new LambdaQueryWrapper<MemberPackage>()
                .eq(MemberPackage::getMemberId, memberId).orderByDesc(MemberPackage::getRemainSessions));
        List<Booking> bookings = bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getMemberId, memberId).orderByDesc(Booking::getStartTime).last("LIMIT 20"));
        List<Object> bookingVOs = bookings.stream().map(b -> {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            CourseTemplate c = courseMapper.selectById(b.getCourseId());
            Coach co = coachMapper.selectById(b.getCoachId());
            map.put("id", b.getId());
            map.put("courseName", c == null ? "课程" : c.getName());
            map.put("coachName", co == null ? "教练" : co.getName());
            map.put("startTime", b.getStartTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            map.put("endTime", b.getEndTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            map.put("status", b.getStatus());
            return (Object) map;
        }).toList();
        return new MemberDetailVO(member, packages, bookingVOs, coach);
    }

    public record MemberDetailVO(Object member, List<?> packages, List<?> bookings, Object coach) {}

    public void updateMember(Long id, AdminDtos.MemberUpdate req) {
        Member m = memberMapper.selectById(id);
        if (m == null) throw new BusinessException("会员不存在");
        if (req.name() != null && !req.name().isBlank()) m.setName(req.name());
        if (req.gender() != null) m.setGender(req.gender());
        if (req.birthday() != null) m.setBirthday(LocalDate.parse(req.birthday()));
        if (req.coachId() != null) {
            Coach coach = coachMapper.selectById(req.coachId());
            if (coach == null || coach.getStatus() != 1) throw new BusinessException("教练不存在或已停用");
            m.setCoachId(req.coachId());
        }
        memberMapper.updateById(m);
        logService.log("UPDATE_MEMBER", "MEMBER", id, "更新会员资料");
    }

    public void rebindCoach(Long memberId, Long coachId, String remark) {
        Member m = memberMapper.selectById(memberId);
        if (m == null) throw new BusinessException("会员不存在");
        Coach coach = coachMapper.selectById(coachId);
        if (coach == null || coach.getStatus() != 1) throw new BusinessException("目标教练不存在或已停用");
        m.setCoachId(coachId);
        memberMapper.updateById(m);
        logService.log("REBIND_COACH", "MEMBER", memberId, remark);
    }

    public MemberPackage addPackage(Long memberId, AdminDtos.PackageAdd req) {
        Member m = memberMapper.selectById(memberId);
        if (m == null) throw new BusinessException("会员不存在");
        CourseTemplate course = courseMapper.selectById(req.courseId());
        if (course == null) throw new BusinessException("课程不存在");
        LocalDate start = req.startDate() == null ? LocalDate.now() : LocalDate.parse(req.startDate());
        LocalDate expire = req.expireDate() == null ? start.plusMonths(6) : LocalDate.parse(req.expireDate());
        MemberPackage p = new MemberPackage();
        p.setMemberId(memberId);
        p.setCourseId(req.courseId());
        p.setTotalSessions(req.totalSessions());
        p.setRemainSessions(req.totalSessions());
        p.setTotalPrice(req.totalPrice() == null ? BigDecimal.ZERO : req.totalPrice());
        p.setStartDate(start);
        p.setExpireDate(expire);
        p.setStatus("ACTIVE");
        packageMapper.insert(p);
        logService.log("ADD_PACKAGE", "MEMBER_PACKAGE", p.getId(), "增购 " + req.totalSessions() + " 节");
        return p;
    }

    public void adjustSessions(Long packageId, AdminDtos.AdjustSessions req) {
        MemberPackage p = packageMapper.selectById(packageId);
        if (p == null) throw new BusinessException("课包不存在");
        int newRemain = p.getRemainSessions() + req.delta();
        if (newRemain < 0) throw new BusinessException("剩余课时不足扣减");
        p.setRemainSessions(newRemain);
        packageMapper.updateById(p);
        logService.log("ADJUST_SESSIONS", "MEMBER_PACKAGE", packageId,
                (req.delta() > 0 ? "增加 " : "扣减 ") + Math.abs(req.delta()) + " 节：" + req.remark());
    }
}
