package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.dto.WechatDtos;
import com.jianshenfang.entity.MemberClaimCode;
import com.jianshenfang.entity.WechatAccount;
import com.jianshenfang.mapper.MemberClaimCodeMapper;
import com.jianshenfang.mapper.WechatAccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 微信身份绑定的写操作集中在这里，保证「写绑定 + 核销认领码 + 日志」在同一事务。
 * 前置校验放在 WechatAuthService（无事务），避免 BusinessException 回滚掉错误次数与待核实申请。
 */
@Service
@RequiredArgsConstructor
public class WechatBindService {

    private final WechatAccountMapper wechatAccountMapper;
    private final MemberClaimCodeMapper claimCodeMapper;
    private final OperationLogService logService;

    /** 会员自助认领：核销认领码后绑定 */
    @Transactional
    public void bind(WechatDtos.Identity id, Long userId, Long memberId, MemberClaimCode code) {
        assertFree(id.openid(), userId);
        insertAccount(id, userId);
        MemberClaimCode used = new MemberClaimCode();
        used.setId(code.getId());
        used.setUsedAt(LocalDateTime.now());
        claimCodeMapper.updateById(used);
        logService.log("CLAIM_MEMBER", "MEMBER", memberId,
                "会员自助认领 openid=" + tail(id.openid()));
    }

    /** 教练/管理员核实身份后代绑：跳过认领码 */
    @Transactional
    public void bindByStaff(WechatDtos.Identity id, Long userId, Long memberId) {
        assertFree(id.openid(), userId);
        insertAccount(id, userId);
        logService.log("STAFF_BIND_WECHAT", "MEMBER", memberId,
                "前台核实后代绑 openid=" + tail(id.openid()));
    }

    @Transactional
    public void unbind(Long userId, Long memberId) {
        wechatAccountMapper.delete(new LambdaQueryWrapper<WechatAccount>()
                .eq(WechatAccount::getUserId, userId));
        logService.log("UNBIND_WECHAT", "MEMBER", memberId, "解绑微信");
    }

    private void assertFree(String openid, Long userId) {
        Long byOpenid = wechatAccountMapper.selectCount(new LambdaQueryWrapper<WechatAccount>()
                .eq(WechatAccount::getOpenid, openid));
        if (byOpenid != null && byOpenid > 0) {
            throw new BusinessException("该微信已绑定其他会员，请联系前台");
        }
        Long byUser = wechatAccountMapper.selectCount(new LambdaQueryWrapper<WechatAccount>()
                .eq(WechatAccount::getUserId, userId));
        if (byUser != null && byUser > 0) {
            throw new BusinessException("该会员已绑定其他微信，请联系前台");
        }
    }

    private void insertAccount(WechatDtos.Identity id, Long userId) {
        WechatAccount wa = new WechatAccount();
        wa.setUserId(userId);
        wa.setAppid(id.appid());
        wa.setOpenid(id.openid());
        wa.setUnionid(id.unionid());
        wa.setAvatarUrl(id.avatarUrl());
        wa.setNickname(id.nickname());
        LocalDateTime now = LocalDateTime.now();
        wa.setCreateTime(now);
        wa.setUpdateTime(now);
        wechatAccountMapper.insert(wa);
    }

    private String tail(String openid) {
        if (openid == null || openid.length() <= 6) return "***";
        return "***" + openid.substring(openid.length() - 6);
    }
}
