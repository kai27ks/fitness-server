package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.dto.BookingDtos;
import com.jianshenfang.entity.*;
import com.jianshenfang.mapper.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CoachSlotsTest {
    CoachMapper coaches = mock(CoachMapper.class);
    CourseTemplateMapper courses = mock(CourseTemplateMapper.class);
    BookingMapper bookings = mock(BookingMapper.class);
    CoachLeaveMapper leaves = mock(CoachLeaveMapper.class);
    MemberPackageMapper packages = mock(MemberPackageMapper.class);
    CoachService service;
    Member member;
    CourseTemplate course;
    LocalDate day = LocalDate.now().plusDays(1);
    @BeforeEach void setup() {
        service = new CoachService(coaches, mock(CoachQualificationMapper.class), leaves, courses, bookings, mock(MemberMapper.class), packages);
        ReflectionTestUtils.setField(service, "openHour", 9); ReflectionTestUtils.setField(service, "closeHour", 21);
        Coach coach = new Coach(); coach.setId(3L); coach.setStatus(1); when(coaches.selectById(3L)).thenReturn(coach);
        course = new CourseTemplate(); course.setId(5L); course.setStatus(1); course.setDurationMin(60); when(courses.selectById(5L)).thenReturn(course);
        member = new Member(); member.setId(1L);
    }
    Booking booking(Long coachId, Long memberId, int hour) {
        Booking b = new Booking(); b.setCoachId(coachId); b.setMemberId(memberId);
        b.setStartTime(day.atTime(hour, 0)); b.setEndTime(day.atTime(hour + 1, 0)); return b;
    }
    @Test void slotsUseTargetMemberIdentityAndMarkOtherCoachConflict() {
        when(bookings.selectList(any(Wrapper.class))).thenReturn(List.of(booking(3L, 1L, 9), booking(3L, 2L, 10), booking(4L, 1L, 11)));
        List<BookingDtos.Slot> slots = service.slotsForMember(3L, day.toString(), 5L, member).slots();
        assertEquals("MINE", slots.get(0).status()); assertEquals("BOOKED", slots.get(1).status());
        assertEquals("MINE", slots.get(2).status()); assertEquals("FREE", slots.get(3).status());
    }
    @Test void leaveDisablesOverlappingSlot() {
        CoachLeave leave = new CoachLeave(); leave.setStartTime(day.atTime(9, 30)); leave.setEndTime(day.atTime(10, 30));
        when(leaves.selectList(any(Wrapper.class))).thenReturn(List.of(leave));
        List<BookingDtos.Slot> slots = service.slotsForMember(3L, day.toString(), 5L, member).slots();
        assertEquals("LEAVE", slots.get(0).status()); assertEquals("LEAVE", slots.get(1).status()); assertEquals("FREE", slots.get(2).status());
    }
    @Test void longDurationStopsWithoutMidnightWrap() {
        course.setDurationMin(600);
        BookingDtos.SlotsResponse response = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> service.slotsForMember(3L, day.toString(), 5L, member));
        assertEquals(1, response.slots().size()); assertEquals("19:00", response.slots().get(0).end());
    }
    @Test void invalidDurationIsRejectedInsteadOfLooping() {
        course.setDurationMin(0);
        assertThrows(BusinessException.class, () -> service.slotsForMember(3L, day.toString(), 5L, member));
    }
}
