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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证：手机号+密码登录、验证码登录、注册、重置密码。
 * 验证码为内存实现（简化），生产应替换为 Redis + 真实短信通道。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final MemberMapper memberMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Value("${app.sms-mock:true}")
    private boolean smsMock;

    /** phone -> 验证码条目 */
    private final Map<String, CodeEntry> codes = new ConcurrentHashMap<>();

    private record CodeEntry(String code, long expireAt, long sentAt) {}

    /** 发送验证码，返回验证码（仅 mock 模式下会回传给前端） */
    public String sendCode(String phone) {
        CodeEntry last = codes.get(phone);
        if (last != null && System.currentTimeMillis() - last.sentAt() < 60_000) {
            throw new BusinessException("验证码发送过于频繁，请 1 分钟后再试");
        }
        String code = String.format("%06d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000));
        codes.put(phone, new CodeEntry(code, System.currentTimeMillis() + 5 * 60_000L, System.currentTimeMillis()));
        return code;
    }

    public boolean smsMock() {
        return smsMock;
    }

    private void verifyCode(String phone, String code) {
        CodeEntry entry = codes.get(phone);
        if (entry == null || entry.expireAt() < System.currentTimeMillis() || !entry.code().equals(code)) {
            throw new BusinessException("验证码错误或已过期");
        }
        codes.remove(phone);
    }

    public AuthDtos.LoginResponse register(AuthDtos.RegisterRequest req) {
        verifyCode(req.phone(), req.code());
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, req.phone()));
        if (exists > 0) {
            throw new BusinessException("该手机号已注册，请直接登录");
        }
        SysUser user = new SysUser();
        user.setPhone(req.phone());
        user.setPassword(encoder.encode(req.password()));
        user.setRole("MEMBER");
        user.setStatus(1);
        userMapper.insert(user);

        String name = (req.name() == null || req.name().isBlank())
                ? "会员" + req.phone().substring(req.phone().length() - 4)
                : req.name();
        Member member = new Member();
        member.setUserId(user.getId());
        member.setName(name);
        member.setPhone(req.phone());
        memberMapper.insert(member);
        return loginSuccess(user);
    }

    public AuthDtos.LoginResponse loginByPassword(AuthDtos.LoginPasswordRequest req) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, req.phone()));
        if (user == null) {
            throw new BusinessException("账号不存在，请先注册");
        }
        checkStatus(user);
        if (!encoder.matches(req.password(), user.getPassword())) {
            throw new BusinessException("手机号或密码错误");
        }
        return loginSuccess(user);
    }

    public AuthDtos.LoginResponse loginByCode(AuthDtos.LoginCodeRequest req) {
        verifyCode(req.phone(), req.code());
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, req.phone()));
        if (user == null) {
            throw new BusinessException("账号不存在，请先注册");
        }
        checkStatus(user);
        return loginSuccess(user);
    }

    public void resetPassword(AuthDtos.ResetPasswordRequest req) {
        verifyCode(req.phone(), req.code());
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, req.phone()));
        if (user == null) {
            throw new BusinessException("账号不存在");
        }
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPassword(encoder.encode(req.password()));
        userMapper.updateById(update);
    }

    private void checkStatus(SysUser user) {
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException("账号已停用，请联系工作室");
        }
    }

    private AuthDtos.LoginResponse loginSuccess(SysUser user) {
        String name = null;
        Long memberId = null;
        if ("MEMBER".equals(user.getRole())) {
            Member member = memberMapper.selectOne(new LambdaQueryWrapper<Member>().eq(Member::getUserId, user.getId()));
            if (member != null) {
                name = member.getName();
                memberId = member.getId();
            }
        }
        return new AuthDtos.LoginResponse(jwtUtil.create(user.getId(), user.getRole()), user.getRole(), name, memberId);
    }
}
