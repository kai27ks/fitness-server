package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.auth.AuthContext;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.config.WxProperties;
import com.jianshenfang.dto.WechatDtos;
import com.jianshenfang.entity.*;
import com.jianshenfang.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** 管理端：认领码、待核实认领申请、微信绑定状态 */
@Service
@RequiredArgsConstructor
public class WechatAdminService {

    private final WxProperties wx;
    private final MemberMapper memberMapper;
    private final MemberClaimCodeMapper claimCodeMapper;
    private final WechatClaimRequestMapper claimRequestMapper;
    private final WechatAccountMapper wechatAccountMapper;
    private final WechatBindService bindService;
    private final OperationLogService logService;

    /** 建档时自动生成，或教练在会员详情页手动重置 */
    @Transactional
    public WechatDtos.ClaimCodeVO resetClaimCode(Long memberId) {
        Member m = requireMember(memberId);
        claimCodeMapper.delete(new LambdaQueryWrapper<MemberClaimCode>()
                .eq(MemberClaimCode::getMemberId, m.getId()));
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        LocalDateTime now = LocalDateTime.now();
        MemberClaimCode cc = new MemberClaimCode();
        cc.setMemberId(m.getId());
        cc.setCode(code);
        cc.setFailCount(0);
        cc.setCreateTime(now);
        cc.setExpireAt(now.plusDays(wx.getClaimCodeExpireDays()));
        claimCodeMapper.insert(cc);
        logService.log("RESET_CLAIM_CODE", "MEMBER", m.getId(), "生成/重置会员认领码");
        return new WechatDtos.ClaimCodeVO(m.getId(), code, cc.getExpireAt(), false);
    }

    public WechatDtos.ClaimCodeVO claimCodeOf(Long memberId) {
        requireMember(memberId);
        MemberClaimCode cc = claimCodeMapper.selectOne(new LambdaQueryWrapper<MemberClaimCode>()
                .eq(MemberClaimCode::getMemberId, memberId));
        if (cc == null) return null;
        return new WechatDtos.ClaimCodeVO(memberId, cc.getCode(), cc.getExpireAt(), cc.getUsedAt() != null);
    }

    public List<WechatDtos.ClaimRequestVO> requests(String status) {
        String s = (status == null || status.isBlank()) ? "PENDING" : status;
        return claimRequestMapper.selectList(new LambdaQueryWrapper<WechatClaimRequest>()
                        .eq(WechatClaimRequest::getStatus, s)
                        .orderByDesc(WechatClaimRequest::getCreateTime))
                .stream()
                .map(r -> new WechatDtos.ClaimRequestVO(r.getId(), r.getOpenid(), r.getPhoneInput(),
                        r.getNickname(), r.getAvatarUrl(), r.getStatus(), r.getCreateTime()))
                .toList();
    }

    /** 教练核实身份后，把申请里的 openid 直接绑到指定会员（无需认领码） */
    @Transactional
    public void bindRequest(Long requestId, Long memberId, String note) {
        WechatClaimRequest r = claimRequestMapper.selectById(requestId);
        if (r == null || !"PENDING".equals(r.getStatus())) {
            throw new BusinessException("该申请不存在或已处理");
        }
        Member m = requireMember(memberId);
        WechatDtos.Identity id = new WechatDtos.Identity(r.getAppid(), r.getOpenid(), r.getUnionid(),
                r.getAvatarUrl(), r.getNickname());
        bindService.bindByStaff(id, m.getUserId(), m.getId());

        WechatClaimRequest upd = new WechatClaimRequest();
        upd.setId(r.getId());
        upd.setStatus("HANDLED");
        upd.setHandleNote(note);
        upd.setHandledBy(AuthContext.userId());
        upd.setHandledAt(LocalDateTime.now());
        claimRequestMapper.updateById(upd);
    }

    public void rejectRequest(Long requestId, String note) {
        WechatClaimRequest r = claimRequestMapper.selectById(requestId);
        if (r == null || !"PENDING".equals(r.getStatus())) {
            throw new BusinessException("该申请不存在或已处理");
        }
        WechatClaimRequest upd = new WechatClaimRequest();
        upd.setId(r.getId());
        upd.setStatus("REJECTED");
        upd.setHandleNote(note);
        upd.setHandledBy(AuthContext.userId());
        upd.setHandledAt(LocalDateTime.now());
        claimRequestMapper.updateById(upd);
        logService.log("REJECT_CLAIM_REQUEST", "WECHAT", r.getId(), "驳回认领申请");
    }

    public WechatDtos.WechatBindVO bindInfo(Long memberId) {
        Member m = requireMember(memberId);
        WechatAccount wa = wechatAccountMapper.selectOne(new LambdaQueryWrapper<WechatAccount>()
                .eq(WechatAccount::getUserId, m.getUserId()));
        if (wa == null) return new WechatDtos.WechatBindVO(false, null, null, null);
        String openid = wa.getOpenid() == null ? "" : wa.getOpenid();
        String tail = openid.length() > 6 ? "***" + openid.substring(openid.length() - 6) : "***";
        return new WechatDtos.WechatBindVO(true, tail, wa.getNickname(), wa.getAvatarUrl());
    }

    public void unbind(Long memberId) {
        Member m = requireMember(memberId);
        bindService.unbind(m.getUserId(), m.getId());
    }

    private Member requireMember(Long memberId) {
        Member m = memberMapper.selectById(memberId);
        if (m == null) throw new BusinessException("会员不存在");
        return m;
    }
}
