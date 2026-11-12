package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.dto.CoachAccountDtos;
import com.jianshenfang.entity.Coach;
import com.jianshenfang.entity.SysUser;
import com.jianshenfang.mapper.CoachMapper;
import com.jianshenfang.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 管理员维护教练档案与登录账号的关联，保留原有会员、课程和预约数据。 */
@Service
@RequiredArgsConstructor
public class CoachAccountService {
    private final CoachMapper coachMapper;
    private final SysUserMapper userMapper;
    private final OperationLogService logService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public List<CoachAccountDtos.Account> list() {
        List<Coach> coaches = coachMapper.selectList(new LambdaQueryWrapper<Coach>().orderByAsc(Coach::getId));
        List<Long> userIds = coaches.stream().map(Coach::getUserId).filter(Objects::nonNull).distinct().toList();
        Map<Long, SysUser> users = userIds.isEmpty() ? Map.of() : userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity()));
        return coaches.stream().map(c -> view(c, c.getUserId() == null ? null : users.get(c.getUserId()))).toList();
    }

    public CoachAccountDtos.Account get(Long coachId) {
        Coach coach = requireCoach(coachMapper.selectById(coachId));
        return view(coach, coach.getUserId() == null ? null : userMapper.selectById(coach.getUserId()));
    }

    @Transactional
    public CoachAccountDtos.Account configure(Long coachId, CoachAccountDtos.Configure req) {
        // 同一档案和同一账号均串行处理，避免重复开户或一个账号被并发关联给两位教练。
        Coach coach = lockCoach(coachId);
        if (coach.getUserId() != null && userMapper.selectById(coach.getUserId()) != null) {
            throw new BusinessException("该教练已有登录账号，请勿重复关联");
        }
        if (req.phone() == null || !req.phone().matches("1\\d{10}")) {
            throw new BusinessException("请输入正确的11位手机号");
        }
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getPhone, req.phone()).last("FOR UPDATE"));
        String action;
        if ("CREATE".equals(req.mode())) {
            if (user != null) throw new BusinessException("该手机号账号已存在，请使用关联已有教练账号");
            requirePassword(req.initialPassword());
            user = new SysUser();
            user.setPhone(req.phone());
            user.setPassword(encoder.encode(req.initialPassword()));
            user.setRole("COACH");
            user.setStatus(1);
            try {
                userMapper.insert(user);
            } catch (DuplicateKeyException e) {
                throw new BusinessException("该手机号账号已存在，请刷新后重试");
            }
            action = "CREATE_COACH_ACCOUNT";
        } else if ("BIND".equals(req.mode())) {
            if (user == null) throw new BusinessException("未找到该手机号账号，请选择创建新账号");
            if (!"COACH".equals(user.getRole())) {
                throw new BusinessException("只能关联已有教练账号，不能关联会员或管理员账号");
            }
            Long count = coachMapper.selectCount(new LambdaQueryWrapper<Coach>()
                    .eq(Coach::getUserId, user.getId()).ne(Coach::getId, coachId));
            if (count > 0) throw new BusinessException("该账号已关联其他教练，不能重复关联");
            action = "BIND_COACH_ACCOUNT";
        } else {
            throw new BusinessException("请选择创建或关联账号");
        }
        coach.setUserId(user.getId());
        coachMapper.updateById(coach);
        logService.log(action, "COACH", coachId, "关联登录账号 ID=" + user.getId());
        return view(coach, user);
    }

    @Transactional
    public void resetPassword(Long coachId, String newPassword) {
        requirePassword(newPassword);
        Coach coach = lockCoach(coachId);
        SysUser user = coach.getUserId() == null ? null : userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, coach.getUserId()).last("FOR UPDATE"));
        if (user == null) throw new BusinessException("请先为该教练创建或关联账号");
        if (!"COACH".equals(user.getRole())) {
            throw new BusinessException("此处只能重置教练账号密码，管理员账号请单独维护");
        }
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPassword(encoder.encode(newPassword));
        userMapper.updateById(update);
        logService.log("RESET_COACH_PASSWORD", "COACH", coachId, "重置教练登录密码");
    }

    private Coach lockCoach(Long coachId) {
        return requireCoach(coachMapper.selectOne(new LambdaQueryWrapper<Coach>()
                .eq(Coach::getId, coachId).last("FOR UPDATE")));
    }

    private Coach requireCoach(Coach coach) {
        if (coach == null) throw new BusinessException("教练不存在");
        return coach;
    }

    private void requirePassword(String password) {
        if (password == null || password.isBlank() || password.length() < 8 || password.length() > 64) {
            throw new BusinessException("请设置8～64位密码");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException("密码过长，请减少字符数量");
        }
    }

    private CoachAccountDtos.Account view(Coach coach, SysUser user) {
        String state = user != null ? "BOUND" : coach.getUserId() == null ? "UNBOUND" : "MISSING";
        return new CoachAccountDtos.Account(coach.getId(), coach.getUserId(), user == null ? null : user.getPhone(),
                user == null ? null : user.getRole(), user == null ? null : user.getStatus(), state);
    }
}
