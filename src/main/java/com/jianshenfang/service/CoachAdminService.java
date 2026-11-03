package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.auth.AuthContext;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.dto.AdminDtos;
import com.jianshenfang.entity.*;
import com.jianshenfang.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 教练管理（管理端 + 教练本人）：列表、新增、资料维护、资质、请假。
 * 请假与已约课程冲突时返回冲突列表，由业务方走取消流程处理（无改期功能）。
 */
@Service
@RequiredArgsConstructor
public class CoachAdminService {

    private final CoachMapper coachMapper;
    private final CoachQualificationMapper qualMapper;
    private final CoachLeaveMapper leaveMapper;
    private final SysUserMapper userMapper;
    private final BookingMapper bookingMapper;
    private final OperationLogService logService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public List<Coach> list(Long status) {
        LambdaQueryWrapper<Coach> w = new LambdaQueryWrapper<Coach>().orderByAsc(Coach::getId);
        if (status != null) w.eq(Coach::getStatus, status);
        return coachMapper.selectList(w);
    }

    /** 当前登录教练（由 sys_user 反查 coach 记录） */
    public Coach currentCoach() {
        Long userId = AuthContext.userId();
        Coach coach = coachMapper.selectOne(new LambdaQueryWrapper<Coach>().eq(Coach::getUserId, userId));
        if (coach == null) throw new BusinessException("未找到教练档案");
        return coach;
    }

    public Coach create(AdminDtos.CoachCreate req) {
        if (req.phone() != null && !req.phone().isBlank()) {
            if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, req.phone())) > 0) {
                throw new BusinessException("该手机号账号已存在");
            }
        }
        SysUser user = null;
        if (req.phone() != null && !req.phone().isBlank()) {
            user = new SysUser();
            user.setPhone(req.phone());
            user.setPassword(encoder.encode("123456"));
            user.setRole("COACH");
            user.setStatus(1);
            userMapper.insert(user);
        }
        Coach c = new Coach();
        c.setName(req.name());
        c.setIntro(req.intro());
        c.setStatus(req.status() == null ? 1 : req.status());
        c.setUserId(user == null ? null : user.getId());
        coachMapper.insert(c);
        logService.log("CREATE_COACH", "COACH", c.getId(), "新增教练 " + req.name());
        return c;
    }

    public void update(Long id, AdminDtos.CoachUpdate req) {
        Coach c = coachMapper.selectById(id);
        if (c == null) throw new BusinessException("教练不存在");
        if (req.name() != null) c.setName(req.name());
        if (req.intro() != null) c.setIntro(req.intro());
        if (req.photo() != null) c.setPhoto(req.photo());
        if (req.status() != null) c.setStatus(req.status());
        coachMapper.updateById(c);
    }

    public List<CoachQualification> qualifications(Long coachId) {
        return qualMapper.selectList(new LambdaQueryWrapper<CoachQualification>()
                .eq(CoachQualification::getCoachId, coachId).orderByDesc(CoachQualification::getExpireDate));
    }

    public CoachQualification addQualification(Long coachId, AdminDtos.QualificationAdd req) {
        CoachQualification q = new CoachQualification();
        q.setCoachId(coachId);
        q.setName(req.name());
        q.setCertNo(req.certNo());
        q.setIssuer(req.issuer());
        q.setImage(req.image());
        q.setExpireDate(req.expireDate() == null ? null : LocalDate.parse(req.expireDate()));
        qualMapper.insert(q);
        return q;
    }

    public void deleteQualification(Long qualId) {
        qualMapper.deleteById(qualId);
    }

    public CoachLeave createLeave(Long coachId, AdminDtos.LeaveCreate req) {
        LocalDateTime start, end;
        try {
            start = LocalDateTime.parse(req.startTime().replace(" ", "T"));
            end = LocalDateTime.parse(req.endTime().replace(" ", "T"));
        } catch (Exception e) {
            throw new BusinessException("时间格式错误（应为 yyyy-MM-dd HH:mm:ss）");
        }
        if (!end.isAfter(start)) throw new BusinessException("结束时间须晚于开始时间");
        CoachLeave leave = new CoachLeave();
        leave.setCoachId(coachId);
        leave.setStartTime(start);
        leave.setEndTime(end);
        leave.setReason(req.reason());
        leave.setStatus(1);
        leaveMapper.insert(leave);
        logService.log("CREATE_LEAVE", "COACH_LEAVE", leave.getId(), "教练请假 " + req.startTime() + "~" + req.endTime());
        return leave;
    }

    public List<CoachLeave> leaves(Long coachId) {
        return leaveMapper.selectList(new LambdaQueryWrapper<CoachLeave>()
                .eq(CoachLeave::getCoachId, coachId).orderByDesc(CoachLeave::getStartTime));
    }

    public void deleteLeave(Long leaveId) {
        leaveMapper.deleteById(leaveId);
    }

    /** 请假冲突预检：返回教练该时段内已存在的预约，供页面提示 */
    public List<Booking> leaveConflict(Long coachId, String startTime, String endTime) {
        LocalDateTime start = LocalDateTime.parse(startTime.replace(" ", "T"));
        LocalDateTime end = LocalDateTime.parse(endTime.replace(" ", "T"));
        return bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getCoachId, coachId)
                .in(Booking::getStatus, "BOOKED", "CHECKED_IN", "COMPLETED")
                .lt(Booking::getStartTime, end)
                .gt(Booking::getEndTime, start));
    }
}
