package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jianshenfang.auth.AuthContext;
import com.jianshenfang.auth.JwtUtil;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.dto.BookingDtos;
import com.jianshenfang.entity.*;
import com.jianshenfang.mapper.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StaffBookingServiceTest {
    @Mock MemberMapper members;
    @Mock CoachMapper coaches;
    @Mock MemberPackageMapper packages;
    @Mock CourseTemplateMapper courses;
    @Mock BookingMapper bookings;
    @Mock BookingService core;
    @Mock CoachService slots;
    @Mock OperationLogService logs;
    StaffBookingService service;
    Member member;
    MemberPackage pkg;

    @BeforeEach void setUp() {
        service = new StaffBookingService(members, coaches, packages, courses, bookings, core, slots, logs);
        member = new Member(); member.setId(1L); member.setCoachId(3L); member.setName("测试会员");
        pkg = new MemberPackage(); pkg.setId(10L); pkg.setMemberId(1L); pkg.setCourseId(5L);
        pkg.setStatus("ACTIVE"); pkg.setRemainSessions(5); pkg.setStartDate(LocalDate.now()); pkg.setExpireDate(LocalDate.now().plusDays(30));
    }
    @AfterEach void clear() { AuthContext.clear(); }
    void asCoach() {
        AuthContext.set(new JwtUtil.AuthUser(30L, "COACH"));
        Coach coach = new Coach(); coach.setId(3L);
        when(coaches.selectOne(any(Wrapper.class))).thenReturn(coach);
    }
    BookingDtos.StaffCreateRequest request() { return new BookingDtos.StaffCreateRequest(1L, 10L, LocalDate.now().plusDays(1).toString(), "10:00"); }

    @Test void coachCreatesForOwnMemberWithServerDerivedCourse() {
        asCoach(); when(members.selectById(1L)).thenReturn(member); when(packages.selectById(10L)).thenReturn(pkg);
        when(core.createForMember(eq(1L), eq(3L), any())).thenReturn(new BookingDtos.CreateSuccess(99L, 5));
        assertEquals(99L, service.create(request()).bookingId());
        verify(core).createForMember(1L, 3L, new BookingDtos.CreateRequest(10L, 5L, request().date(), "10:00"));
        verify(logs).log("STAFF_CREATE_BOOKING", "BOOKING", 99L, "代会员 ID=1 约课");
    }
    @Test void adminMayBookMemberOfAnyCoach() {
        AuthContext.set(new JwtUtil.AuthUser(90L, "ADMIN"));
        when(members.selectById(1L)).thenReturn(member); when(packages.selectById(10L)).thenReturn(pkg);
        when(core.createForMember(eq(1L), isNull(), any())).thenReturn(new BookingDtos.CreateSuccess(99L, 5));
        service.create(request());
        verify(core).createForMember(eq(1L), isNull(), any());
    }
    @Test void coachCannotReadOrBookOtherCoachMember() {
        asCoach(); member.setCoachId(4L); when(members.selectById(1L)).thenReturn(member);
        assertEquals(403, assertThrows(BusinessException.class, () -> service.options(1L)).getCode());
        assertThrows(BusinessException.class, () -> service.slots(1L, 10L, request().date()));
        assertThrows(BusinessException.class, () -> service.create(request()));
        verifyNoInteractions(packages, core, slots);
    }
    @Test void rejectsAnotherMembersPackage() {
        asCoach(); when(members.selectById(1L)).thenReturn(member); pkg.setMemberId(2L);
        when(packages.selectById(10L)).thenReturn(pkg);
        assertThrows(BusinessException.class, () -> service.create(request()));
        verifyNoInteractions(core);
    }
    @Test void slotsUseTargetMemberRatherThanLoggedInCoach() {
        asCoach(); when(members.selectById(1L)).thenReturn(member); when(packages.selectById(10L)).thenReturn(pkg);
        service.slots(1L, 10L, request().date());
        verify(slots).slotsForMember(3L, request().date(), 5L, member);
    }
    @Test void expiredDateCannotLoadSlots() {
        asCoach(); when(members.selectById(1L)).thenReturn(member); when(packages.selectById(10L)).thenReturn(pkg);
        assertThrows(BusinessException.class, () -> service.slots(1L, 10L, pkg.getExpireDate().plusDays(1).toString()));
        verifyNoInteractions(slots);
    }
    @Test void coachCannotCancelOrCheckInAnotherCoachsBooking() {
        asCoach(); Booking b = new Booking(); b.setCoachId(4L); when(bookings.selectById(99L)).thenReturn(b);
        assertEquals(403, assertThrows(BusinessException.class, () -> service.requireBookingAccess(99L)).getCode());
    }
    @Test void memberRoleCannotUseDelegatedBooking() {
        AuthContext.set(new JwtUtil.AuthUser(1L, "MEMBER"));
        assertThrows(BusinessException.class, () -> service.members(null));
        assertThrows(BusinessException.class, () -> service.options(1L));
        assertThrows(BusinessException.class, () -> service.create(request()));
        verifyNoInteractions(members, core);
    }
}
