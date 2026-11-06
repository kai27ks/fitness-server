package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.dto.AdminDtos;
import com.jianshenfang.dto.WechatDtos;
import com.jianshenfang.entity.*;
import com.jianshenfang.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final WechatAdminService wechatAdminService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** 课包到期前 N 天判定为「即将到期」，可在 application.yml 配置 */
    @Value("${app.package.expiring-days:7}")
    private int expiringDays;

    public List<AdminDtos.MemberAdminVO> list(String keyword, Long coachId) {
        LambdaQueryWrapper<Member> w = new LambdaQueryWrapper<Member>().orderByDesc(Member::getId);
        if (keyword != null && !keyword.isBlank()) {
            w.and(x -> x.like(Member::getName, keyword).or().like(Member::getPhone, keyword));
        }
        if (coachId != null) w.eq(Member::getCoachId, coachId);

        LocalDate today = LocalDate.now();
        return memberMapper.selectList(w).stream().map(m -> {
            Coach coach = m.getCoachId() == null ? null : coachMapper.selectById(m.getCoachId());
            List<MemberPackage> pkgs = packageMapper.selectList(new LambdaQueryWrapper<MemberPackage>()
                    .eq(MemberPackage::getMemberId, m.getId())
                    .orderByAsc(MemberPackage::getExpireDate));

            List<AdminDtos.MemberCourseVO> courses = pkgs.stream().map(p -> {
                CourseTemplate c = courseMapper.selectById(p.getCourseId());
                return new AdminDtos.MemberCourseVO(p.getId(), p.getCourseId(),
                        c == null ? "课程 #" + p.getCourseId() : c.getName(),
                        p.getRemainSessions(), p.getTotalSessions(),
                        p.getExpireDate() == null ? "" : p.getExpireDate().toString());
            }).toList();

            int remain = pkgs.stream().mapToInt(MemberPackage::getRemainSessions).sum();
            int total = pkgs.stream().mapToInt(MemberPackage::getTotalSessions).sum();

            String status;
            if (pkgs.isEmpty()) {
                status = "EMPTY";
            } else if (pkgs.stream().allMatch(p -> p.getRemainSessions() <= 0)) {
                status = "USED";
            } else if (pkgs.stream().anyMatch(p -> p.getRemainSessions() > 0 && p.getExpireDate() != null
                    && p.getExpireDate().isBefore(today))) {
                status = "EXPIRED";
            } else if (pkgs.stream().anyMatch(p -> p.getRemainSessions() > 0 && p.getExpireDate() != null
                    && !p.getExpireDate().isBefore(today)
                    && !p.getExpireDate().isAfter(today.plusDays(expiringDays)))) {
                status = "EXPIRING";
            } else {
                status = "NORMAL";
            }

            return new AdminDtos.MemberAdminVO(m.getId(), m.getName(), m.getPhone(), m.getGender(),
                    m.getBirthday() == null ? null : m.getBirthday().toString(),
                    m.getCoachId(), coach == null ? null : coach.getName(),
                    remain, total, courses, status);
        }).toList();
    }

    /** 建档：同时创建登录账号与会员档案，并生成线下认领码交给会员 */
    @Transactional
    public AdminDtos.MemberCreateVO create(AdminDtos.MemberCreate req) {
        String phone = req.phone().trim();
        if (!phone.matches("^1\\d{10}$")) {
            throw new BusinessException("请输入正确的手机号");
        }
        // 两张表都有 uk_phone，必须都查，否则会撞唯一键变成 500
        if (memberMapper.selectCount(new LambdaQueryWrapper<Member>().eq(Member::getPhone, phone)) > 0) {
            throw new BusinessException("该手机号会员已存在");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, phone)) > 0) {
            throw new BusinessException("该手机号账号已存在");
        }
        if (req.coachId() != null) {
            Coach coach = coachMapper.selectById(req.coachId());
            if (coach == null || coach.getStatus() != 1) {
                throw new BusinessException("绑定教练不存在或已停用");
            }
        }
        SysUser user = new SysUser();
        user.setPhone(phone);
        user.setPassword(encoder.encode(req.password() == null ? "123456" : req.password()));
        user.setRole("MEMBER");
        user.setStatus(1);
        userMapper.insert(user);

        Member m = new Member();
        m.setUserId(user.getId());
        m.setName(req.name());
        m.setPhone(phone);
        m.setGender(req.gender());
        m.setBirthday(req.birthday() == null ? null : LocalDate.parse(req.birthday()));
        m.setCoachId(req.coachId());
        memberMapper.insert(m);
        logService.log("CREATE_MEMBER", "MEMBER", m.getId(), "代录会员 " + phone);

        WechatDtos.ClaimCodeVO code = wechatAdminService.resetClaimCode(m.getId());
        return new AdminDtos.MemberCreateVO(m.getId(), m.getName(), m.getPhone(),
                code.code(), code.expireAt() == null ? null : code.expireAt().toString());
    }

    public MemberDetailVO detail(Long memberId) {
        Member member = memberMapper.selectById(memberId);
        if (member == null) throw new BusinessException("会员不存在");
        Coach coach = member.getCoachId() == null ? null : coachMapper.selectById(member.getCoachId());
        List<MemberPackage> packages = packageMapper.selectList(new LambdaQueryWrapper<MemberPackage>()
                .eq(MemberPackage::getMemberId, memberId).orderByDesc(MemberPackage::getRemainSessions));
        List<AdminDtos.MemberPackageVO> packageVOs = packages.stream().map(p -> {
            CourseTemplate c = courseMapper.selectById(p.getCourseId());
            return new AdminDtos.MemberPackageVO(p.getId(), p.getCourseId(),
                    c == null ? "课程 #" + p.getCourseId() : c.getName(),
                    p.getRemainSessions(), p.getTotalSessions(),
                    p.getTotalPrice() == null ? "0" : p.getTotalPrice().toPlainString(),
                    p.getExpireDate() == null ? "" : p.getExpireDate().toString(),
                    p.getStatus());
        }).toList();
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
        return new MemberDetailVO(member, packageVOs, bookingVOs, coach);
    }

    public record MemberDetailVO(Object member, List<?> packages, List<?> bookings, Object coach) {}

    /** 编辑资料：手机号在 sys_user 与 member 各存一份，必须在同一事务里同步 */
    @Transactional
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
        if (req.phone() != null && !req.phone().isBlank()) {
            String newPhone = req.phone().trim();
            if (!newPhone.matches("^1\\d{10}$")) throw new BusinessException("请输入正确的手机号");
            if (!newPhone.equals(m.getPhone())) {
                if (memberMapper.selectCount(new LambdaQueryWrapper<Member>().eq(Member::getPhone, newPhone)) > 0) {
                    throw new BusinessException("该手机号已被其他会员使用");
                }
                if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, newPhone)) > 0) {
                    throw new BusinessException("该手机号已被其他账号使用");
                }
                SysUser user = userMapper.selectById(m.getUserId());
                if (user == null) throw new BusinessException("登录账号不存在");
                SysUser upd = new SysUser();
                upd.setId(user.getId());
                upd.setPhone(newPhone);
                userMapper.updateById(upd);
                logService.log("UPDATE_MEMBER_PHONE", "MEMBER", id,
                        "手机号 " + m.getPhone() + " -> " + newPhone);
                m.setPhone(newPhone);
            }
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

    /** 全部会员课包（管理端课包页），支持按会员姓名/手机号搜索 */
    public List<AdminDtos.PackageAdminVO> allPackages(String keyword) {
        LambdaQueryWrapper<MemberPackage> w = new LambdaQueryWrapper<MemberPackage>()
                .orderByAsc(MemberPackage::getExpireDate);
        if (keyword != null && !keyword.isBlank()) {
            List<Member> members = memberMapper.selectList(new LambdaQueryWrapper<Member>()
                    .and(x -> x.like(Member::getName, keyword).or().like(Member::getPhone, keyword)));
            if (members.isEmpty()) return List.of();
            w.in(MemberPackage::getMemberId, members.stream().map(Member::getId).toList());
        }
        return packageMapper.selectList(w).stream().map(p -> {
            Member m = memberMapper.selectById(p.getMemberId());
            CourseTemplate c = courseMapper.selectById(p.getCourseId());
            return new AdminDtos.PackageAdminVO(p.getId(), p.getMemberId(),
                    m == null ? "会员" : m.getName(),
                    c == null ? "课程" : c.getName(),
                    p.getRemainSessions(), p.getTotalSessions(),
                    p.getTotalPrice() == null ? "0" : p.getTotalPrice().toPlainString(),
                    p.getExpireDate() == null ? "" : p.getExpireDate().toString(),
                    p.getStatus());
        }).toList();
    }

    public MemberPackage addPackage(Long memberId, AdminDtos.PackageAdd req) {
        Member m = memberMapper.selectById(memberId);
        if (m == null) throw new BusinessException("会员不存在");
        CourseTemplate course = courseMapper.selectById(req.courseId());
        if (course == null) throw new BusinessException("课程不存在");
        LocalDate start = req.startDate() == null ? LocalDate.now() : LocalDate.parse(req.startDate());
        LocalDate expire = req.expireDate() == null ? start.plusMonths(6) : LocalDate.parse(req.expireDate());
        BigDecimal price = req.totalPrice() == null ? BigDecimal.ZERO : req.totalPrice();
        int sessions = req.totalSessions() == null ? 0 : req.totalSessions();
        if (sessions <= 0) {
            throw new BusinessException("增购节数须大于 0");
        }

        // 同会员同课程只能有一条课包（uk_member_course）：已存在则并入，避免唯一键冲突
        MemberPackage exist = packageMapper.selectOne(new LambdaQueryWrapper<MemberPackage>()
                .eq(MemberPackage::getMemberId, memberId)
                .eq(MemberPackage::getCourseId, req.courseId()));
        if (exist != null) {
            exist.setTotalSessions(exist.getTotalSessions() + sessions);
            exist.setRemainSessions(exist.getRemainSessions() + sessions);
            exist.setTotalPrice((exist.getTotalPrice() == null ? BigDecimal.ZERO : exist.getTotalPrice()).add(price));
            if (expire.isAfter(exist.getExpireDate())) {
                exist.setExpireDate(expire);
            }
            exist.setStatus("ACTIVE");
            packageMapper.updateById(exist);
            logService.log("ADD_PACKAGE", "MEMBER_PACKAGE", exist.getId(), "增购并入 " + sessions + " 节");
            return exist;
        }

        MemberPackage p = new MemberPackage();
        p.setMemberId(memberId);
        p.setCourseId(req.courseId());
        p.setTotalSessions(sessions);
        p.setRemainSessions(sessions);
        p.setTotalPrice(price);
        p.setStartDate(start);
        p.setExpireDate(expire);
        p.setStatus("ACTIVE");
        try {
            packageMapper.insert(p);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException("该课程课包已存在，请刷新后重试");
        }
        logService.log("ADD_PACKAGE", "MEMBER_PACKAGE", p.getId(), "新增课包 " + sessions + " 节");
        return p;
    }

    public void adjustSessions(Long packageId, AdminDtos.AdjustSessions req) {
        MemberPackage p = packageMapper.selectById(packageId);
        if (p == null) throw new BusinessException("课包不存在");
        int newRemain = (int) (p.getRemainSessions() + req.delta());
        if (newRemain < 0) throw new BusinessException("剩余课时不足扣减");
        p.setRemainSessions(newRemain);
        packageMapper.updateById(p);
        logService.log("ADJUST_SESSIONS", "MEMBER_PACKAGE", packageId,
                (req.delta() > 0 ? "增加 " : "扣减 ") + Math.abs(req.delta()) + " 节：" + req.remark());
    }
}
