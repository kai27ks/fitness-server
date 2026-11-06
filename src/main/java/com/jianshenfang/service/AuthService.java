package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.auth.JwtUtil;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.dto.AuthDtos;
import com.jianshenfang.entity.Member;
import com.jianshenfang.entity.SysUser;
import com.jianshenfang.mapper.MemberMapper;
import com.jianshenfang.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证：仅保留「手机号 + 密码」登录。
 *
 * 按设计稿 Q2=A 已下线短信验证码链路（sms-code / register / login-code / password-reset）：
 * 新会员由教练/管理员建档，或从公众号用「手机号 + 认领码」认领既有档案，
 * 不再提供自助注册与自助找回密码。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final MemberMapper memberMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthDtos.LoginResponse loginByPassword(AuthDtos.LoginPasswordRequest req) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getPhone, req.phone()));
        if (user == null) {
            throw new BusinessException("账号不存在，请联系前台建档");
        }
        checkStatus(user);
        if (!encoder.matches(req.password(), user.getPassword())) {
            throw new BusinessException("手机号或密码错误");
        }
        return loginSuccess(user);
    }

    private void checkStatus(SysUser user) {
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException("账号已停用，请联系工作室");
        }
    }

    /**
     * 签发既有账号的 JWT。微信认领成功后复用此方法，
     * 使会员端业务通过 AuthContext.userId() -> member.id 直接看到原有课包/预约/风采，无需数据迁移。
     */
    public AuthDtos.LoginResponse loginSuccess(SysUser user) {
        String name = null;
        Long memberId = null;
        if ("MEMBER".equals(user.getRole())) {
            Member member = memberMapper.selectOne(new LambdaQueryWrapper<Member>()
                    .eq(Member::getUserId, user.getId()));
            if (member != null) {
                name = member.getName();
                memberId = member.getId();
            }
        }
        return new AuthDtos.LoginResponse(jwtUtil.create(user.getId(), user.getRole()),
                user.getRole(), name, memberId);
    }
}
