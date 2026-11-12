package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.dto.CoachAccountDtos;
import com.jianshenfang.entity.Coach;
import com.jianshenfang.entity.SysUser;
import com.jianshenfang.mapper.CoachMapper;
import com.jianshenfang.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CoachAccountServiceTest {
    @Mock private CoachMapper coaches;
    @Mock private SysUserMapper users;
    @Mock private OperationLogService logs;
    private CoachAccountService service;
    private Coach coach;

    @BeforeEach
    void setUp() {
        service = new CoachAccountService(coaches, users, logs);
        coach = new Coach(); coach.setId(3L); coach.setName("林珊"); coach.setStatus(1);
    }

    private SysUser user(String role) {
        SysUser user = new SysUser(); user.setId(20L); user.setPhone("13800138000");
        user.setRole(role); user.setStatus(1); user.setPassword("existing-hash"); return user;
    }

    private void lockCoach() {
        when(coaches.selectOne(any(Wrapper.class))).thenReturn(coach);
    }

    @Test
    void listsUnboundArchivesWithoutLookingUpNullUserIds() {
        when(coaches.selectList(any(Wrapper.class))).thenReturn(List.of(coach));
        var accounts = service.list();
        assertEquals("UNBOUND", accounts.get(0).state());
        assertNull(accounts.get(0).phone());
        verifyNoInteractions(users);
    }

    @Test
    void distinguishesMissingAccountFromNeverConfiguredArchive() {
        coach.setUserId(99L);
        when(coaches.selectById(3L)).thenReturn(coach);
        assertEquals("MISSING", service.get(3L).state());
    }

    @Test
    void createsEncryptedCoachLoginAndLinksExistingArchive() {
        lockCoach();
        when(users.insert(any(SysUser.class))).thenAnswer(call -> {
            call.<SysUser>getArgument(0).setId(20L); return 1;
        });
        var account = service.configure(3L, new CoachAccountDtos.Configure("CREATE", "13800138000", "ValidPass123!"));
        ArgumentCaptor<SysUser> created = ArgumentCaptor.forClass(SysUser.class);
        verify(users).insert(created.capture());
        assertTrue(new BCryptPasswordEncoder().matches("ValidPass123!", created.getValue().getPassword()));
        assertEquals("COACH", created.getValue().getRole());
        assertEquals(1, created.getValue().getStatus());
        assertEquals(20L, coach.getUserId());
        assertEquals("BOUND", account.state());
        verify(coaches).updateById(coach);
        verify(logs).log("CREATE_COACH_ACCOUNT", "COACH", 3L, "关联登录账号 ID=20");
    }

    @Test
    void bindsUnassignedCoachAccountWithoutChangingPasswordOrRole() {
        lockCoach(); SysUser user = user("COACH");
        when(users.selectOne(any(Wrapper.class))).thenReturn(user);
        when(coaches.selectCount(any(Wrapper.class))).thenReturn(0L);
        var account = service.configure(3L, new CoachAccountDtos.Configure("BIND", user.getPhone(), null));
        assertEquals(20L, account.userId());
        assertEquals("existing-hash", user.getPassword());
        verify(users, never()).insert(any(SysUser.class));
        verify(users, never()).updateById(any(SysUser.class));
        verify(coaches).updateById(coach);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "MEMBER"})
    void rejectsBindingAnotherRole(String role) {
        lockCoach(); when(users.selectOne(any(Wrapper.class))).thenReturn(user(role));
        assertThrows(BusinessException.class, () -> service.configure(3L,
                new CoachAccountDtos.Configure("BIND", "13800138000", null)));
        verify(coaches, never()).updateById(any(Coach.class));
        verifyNoInteractions(logs);
    }

    @Test
    void rejectsAccountAlreadyLinkedToAnotherCoach() {
        lockCoach(); when(users.selectOne(any(Wrapper.class))).thenReturn(user("COACH"));
        when(coaches.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertThrows(BusinessException.class, () -> service.configure(3L,
                new CoachAccountDtos.Configure("BIND", "13800138000", null)));
        verify(coaches, never()).updateById(any(Coach.class));
    }

    @Test
    void refusesToReplaceAnExistingBinding() {
        lockCoach(); coach.setUserId(20L); when(users.selectById(20L)).thenReturn(user("COACH"));
        assertThrows(BusinessException.class, () -> service.configure(3L,
                new CoachAccountDtos.Configure("CREATE", "13900139000", "ValidPass123!")));
        verify(users, never()).insert(any(SysUser.class));
        verify(coaches, never()).updateById(any(Coach.class));
    }

    @Test
    void rejectsDuplicatePhoneOnCreation() {
        lockCoach(); when(users.selectOne(any(Wrapper.class))).thenReturn(user("COACH"));
        assertThrows(BusinessException.class, () -> service.configure(3L,
                new CoachAccountDtos.Configure("CREATE", "13800138000", "ValidPass123!")));
        verify(users, never()).insert(any(SysUser.class));
    }

    @Test
    void cannotBindMissingAccount() {
        lockCoach();
        assertThrows(BusinessException.class, () -> service.configure(3L,
                new CoachAccountDtos.Configure("BIND", "13800138000", null)));
        verify(coaches, never()).updateById(any(Coach.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "123456", "        "})
    void rejectsWeakOrMissingInitialPassword(String password) {
        lockCoach();
        assertThrows(BusinessException.class, () -> service.configure(3L,
                new CoachAccountDtos.Configure("CREATE", "13800138000", password)));
        verify(users, never()).insert(any(SysUser.class));
    }

    @Test
    void resetOnlyChangesEncryptedPassword() {
        lockCoach(); coach.setUserId(20L); when(users.selectOne(any(Wrapper.class))).thenReturn(user("COACH"));
        service.resetPassword(3L, "Replacement123!");
        ArgumentCaptor<SysUser> update = ArgumentCaptor.forClass(SysUser.class);
        verify(users).updateById(update.capture());
        assertEquals(20L, update.getValue().getId());
        assertTrue(new BCryptPasswordEncoder().matches("Replacement123!", update.getValue().getPassword()));
        assertNull(update.getValue().getRole());
        assertNull(update.getValue().getPhone());
        verify(logs).log("RESET_COACH_PASSWORD", "COACH", 3L, "重置教练登录密码");
    }

    @Test
    void preservesLegacyAdminAccountBinding() {
        coach.setUserId(20L); when(coaches.selectById(3L)).thenReturn(coach);
        when(users.selectById(20L)).thenReturn(user("ADMIN"));
        assertEquals("ADMIN", service.get(3L).role());
        lockCoach(); when(users.selectOne(any(Wrapper.class))).thenReturn(user("ADMIN"));
        assertThrows(BusinessException.class, () -> service.resetPassword(3L, "Replacement123!"));
        verify(users, never()).updateById(any(SysUser.class));
    }
}
