package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.config.WxProperties;
import com.jianshenfang.dto.AuthDtos;
import com.jianshenfang.dto.WechatDtos;
import com.jianshenfang.entity.*;
import com.jianshenfang.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WechatAuthServiceTest {
    @Mock private WechatAccountMapper accounts;
    @Mock private MemberClaimCodeMapper codes;
    @Mock private WechatTempTokenMapper tickets;
    @Mock private WechatClaimRequestMapper requests;
    @Mock private MemberMapper members;
    @Mock private SysUserMapper users;
    @Mock private AuthService auth;
    @Mock private WechatBindService bind;
    @Mock private WxApiClient wxApi;
    @Mock private OperationLogService logs;
    private WechatAuthService service;
    private WechatTempToken ticket;
    private Member member;
    private MemberClaimCode code;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WechatTempToken.class);
        WxProperties wx = new WxProperties();
        wx.setMock(true);
        service = new WechatAuthService(wx, new ObjectMapper(), accounts, codes, tickets,
                requests, members, users, auth, bind, wxApi, logs);
        ticket = new WechatTempToken();
        ticket.setToken("ticket");
        ticket.setType("CLAIM_TICKET");
        ticket.setPayload("{\"appid\":\"app\",\"openid\":\"openid\"}");
        ticket.setExpireAt(LocalDateTime.now().plusMinutes(5));
        member = new Member();
        member.setId(1L);
        member.setUserId(2L);
        code = new MemberClaimCode();
        code.setId(3L);
        code.setCode("123456");
        code.setExpireAt(LocalDateTime.now().plusDays(1));
    }

    @Test
    void wrongCodeCanBeRetriedWithSameTicketAndSuccessfulClaimConsumesIt() {
        when(tickets.selectById("ticket")).thenReturn(ticket);
        when(members.selectOne(any(Wrapper.class))).thenReturn(member);
        when(codes.selectOne(any(Wrapper.class))).thenReturn(code);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.claim(new WechatDtos.ClaimRequest("ticket", "13800138000", "000000")));
        assertTrue(error.getMessage().contains("认领码不正确"));
        verify(codes).updateById(any(MemberClaimCode.class));
        verify(tickets, never()).update(isNull(), any(Wrapper.class));

        SysUser user = new SysUser();
        user.setId(2L);
        user.setStatus(1);
        when(users.selectById(2L)).thenReturn(user);
        when(tickets.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(auth.loginSuccess(user)).thenReturn(new AuthDtos.LoginResponse("jwt", "MEMBER", "会员", 1L));
        var result = service.claim(new WechatDtos.ClaimRequest("ticket", "13800138000", "123456"));
        assertEquals("jwt", result.token());
        verify(tickets).update(isNull(), any(Wrapper.class));
        verify(bind).bind(any(WechatDtos.Identity.class), eq(2L), eq(1L), eq(code));
    }

    @Test
    void rejectsAlreadyUsedTicketBeforeBinding() {
        ticket.setUsedAt(LocalDateTime.now());
        when(tickets.selectById("ticket")).thenReturn(ticket);
        assertThrows(BusinessException.class,
                () -> service.claim(new WechatDtos.ClaimRequest("ticket", "13800138000", "123456")));
        verifyNoInteractions(bind);
    }

    @Test
    void rejectsTicketConsumedByConcurrentRequest() {
        when(tickets.selectById("ticket")).thenReturn(ticket);
        when(members.selectOne(any(Wrapper.class))).thenReturn(member);
        when(codes.selectOne(any(Wrapper.class))).thenReturn(code);
        SysUser user = new SysUser();
        user.setStatus(1);
        when(users.selectById(2L)).thenReturn(user);
        when(tickets.update(isNull(), any(Wrapper.class))).thenReturn(0);
        assertThrows(BusinessException.class,
                () -> service.claim(new WechatDtos.ClaimRequest("ticket", "13800138000", "123456")));
        verifyNoInteractions(bind);
    }
}
