package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.dto.BookingDtos;
import com.jianshenfang.entity.*;
import com.jianshenfang.mapper.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingCreationTest {
    @Mock BookingMapper bookings;
    @Mock MemberMapper members;
    @Mock CoachMapper coaches;
    @Mock CoachLeaveMapper leaves;
    @Mock CourseTemplateMapper courses;
    @Mock MemberPackageMapper packages;
    @Mock MemberService memberService;
    @Mock NotifyService notifications;
    BookingService service;
    Member member;
    MemberPackage pkg;
    CourseTemplate course;
    Coach coach;
    @BeforeEach void setup() {
        service = new BookingService(bookings, members, coaches, leaves, courses, packages, memberService, notifications);
        ReflectionTestUtils.setField(service, "openHour", 9); ReflectionTestUtils.setField(service, "closeHour", 21);
        member = new Member(); member.setId(1L); member.setUserId(11L); member.setCoachId(3L); member.setName("会员"); member.setPhone("13800138000");
        pkg = new MemberPackage(); pkg.setId(10L); pkg.setMemberId(1L); pkg.setCourseId(5L); pkg.setStatus("ACTIVE"); pkg.setRemainSessions(5);
        pkg.setStartDate(LocalDate.now()); pkg.setExpireDate(LocalDate.now().plusDays(30));
        course = new CourseTemplate(); course.setId(5L); course.setName("私教"); course.setDurationMin(60); course.setStatus(1);
        coach = new Coach(); coach.setId(3L); coach.setName("教练"); coach.setStatus(1);
        when(members.selectOne(any(Wrapper.class))).thenReturn(member);
    }
    void validData() {
        when(packages.selectById(10L)).thenReturn(pkg); when(courses.selectById(5L)).thenReturn(course);
        when(coaches.selectOne(any(Wrapper.class))).thenReturn(coach);
    }
    BookingDtos.CreateRequest request() { return new BookingDtos.CreateRequest(10L, 5L, LocalDate.now().plusDays(1).toString(), "10:00"); }
    @Test void selfAndStaffShareCreationWithoutImmediateDeduction() {
        validData(); when(memberService.currentMember(11L)).thenReturn(member);
        when(bookings.insert(any(Booking.class))).thenAnswer(call -> { call.<Booking>getArgument(0).setId(99L); return 1; });
        assertEquals(99L, service.create(11L, request()).bookingId());
        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class); verify(bookings).insert(saved.capture());
        assertEquals(1L, saved.getValue().getMemberId()); assertEquals(3L, saved.getValue().getCoachId());
        assertEquals(Booking.BOOKED, saved.getValue().getStatus());
        verify(packages, never()).deductOne(anyLong());
        verify(notifications).send(eq("BOOKING_CREATED"), eq(member.getPhone()), eq(99L), anyString());
    }
    @Test void rebindBetweenAuthorizationAndCreationIsRejected() {
        member.setCoachId(4L);
        assertEquals(403, assertThrows(BusinessException.class, () -> service.createForMember(1L, 3L, request())).getCode());
        verifyNoInteractions(packages, bookings);
    }
    @Test void cannotBookBeyondPackageExpiry() {
        validData(); pkg.setExpireDate(LocalDate.now());
        assertThrows(BusinessException.class, () -> service.createForMember(1L, 3L, request()));
        verify(bookings, never()).insert(any(Booking.class));
    }
    @Test void rejectsEmptyPackage() {
        pkg.setRemainSessions(0); when(packages.selectById(10L)).thenReturn(pkg);
        assertThrows(BusinessException.class, () -> service.createForMember(1L, 3L, request()));
        verifyNoInteractions(bookings);
    }
    @Test void rejectsCoachLeave() {
        validData(); when(leaves.selectList(any(Wrapper.class))).thenReturn(List.of(new CoachLeave()));
        assertThrows(BusinessException.class, () -> service.createForMember(1L, 3L, request()));
        verify(bookings, never()).insert(any(Booking.class));
    }
    @Test void rejectsOverlappingCoachBooking() {
        validData(); when(bookings.selectList(any(Wrapper.class))).thenReturn(List.of(new Booking()));
        assertThrows(BusinessException.class, () -> service.createForMember(1L, 3L, request()));
        verify(bookings, never()).insert(any(Booking.class));
    }
    @Test void rejectsMemberConflictEvenIfCoachIsFree() {
        validData(); when(bookings.selectList(any(Wrapper.class))).thenReturn(List.of(), List.of(new Booking()));
        assertThrows(BusinessException.class, () -> service.createForMember(1L, 3L, request()));
        verify(bookings, never()).insert(any(Booking.class));
    }
    @Test void translatesConcurrentUniqueConflict() {
        validData(); when(bookings.insert(any(Booking.class))).thenThrow(new DuplicateKeyException("conflict"));
        assertThrows(BusinessException.class, () -> service.createForMember(1L, 3L, request()));
        verifyNoInteractions(notifications);
    }
}
