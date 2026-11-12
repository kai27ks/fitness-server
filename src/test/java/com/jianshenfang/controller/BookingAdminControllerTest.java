package com.jianshenfang.controller;

import com.jianshenfang.auth.*;
import com.jianshenfang.common.*;
import com.jianshenfang.dto.*;
import com.jianshenfang.entity.Coach;
import com.jianshenfang.service.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookingAdminControllerTest {
    BookingService bookings;
    CoachAdminService coaches;
    StaffBookingService staff;
    OperationLogService logs;
    MockMvc mvc;
    @BeforeEach void setup() {
        bookings = mock(BookingService.class); coaches = mock(CoachAdminService.class);
        staff = mock(StaffBookingService.class); logs = mock(OperationLogService.class);
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.parse(anyString())).thenAnswer(c -> new JwtUtil.AuthUser(11L, c.getArgument(0)));
        mvc = MockMvcBuilders.standaloneSetup(new BookingAdminController(bookings, coaches, logs, staff))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(new AuthInterceptor(jwt)).build();
    }
    @AfterEach void cleanup() { AuthContext.clear(); }
    @Test void unauthenticatedCannotAccessDelegateEndpoints() throws Exception {
        mvc.perform(get("/api/admin/bookings/members")).andExpect(status().isUnauthorized());
        verifyNoInteractions(staff);
    }
    @Test void memberCannotDelegateOrReadStaffMembers() throws Exception {
        mvc.perform(get("/api/admin/bookings/members").header("Authorization", "Bearer MEMBER")).andExpect(jsonPath("$.code").value(400));
        mvc.perform(post("/api/admin/bookings").header("Authorization", "Bearer MEMBER").contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\":1,\"packageId\":10,\"date\":\"2026-12-01\",\"startTime\":\"10:00\"}"))
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(staff, bookings);
    }
    @Test void coachCannotOverrideOwnCoachFilter() throws Exception {
        Coach coach = new Coach(); coach.setId(3L); when(coaches.currentCoach()).thenReturn(coach);
        when(bookings.adminList(isNull(), any())).thenReturn(List.of());
        mvc.perform(get("/api/admin/bookings?coachId=999&status=BOOKED").header("Authorization", "Bearer COACH"))
                .andExpect(jsonPath("$.code").value(0));
        verify(bookings).adminList(isNull(), argThat(q -> q.coachId().equals(3L) && "BOOKED".equals(q.status())));
    }
    @Test void adminCanSelectCoachFilter() throws Exception {
        when(bookings.adminList(isNull(), any())).thenReturn(List.of());
        mvc.perform(get("/api/admin/bookings?coachId=9").header("Authorization", "Bearer ADMIN"));
        verify(bookings).adminList(isNull(), argThat(q -> q.coachId().equals(9L)));
        verifyNoInteractions(coaches);
    }
    @Test void incompleteCreateNeverReachesService() throws Exception {
        mvc.perform(post("/api/admin/bookings").header("Authorization", "Bearer COACH").contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\":1,\"date\":\"2026-12-01\",\"startTime\":\"10:00\"}"))
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(staff);
    }
    @Test void cancelAndCheckinAreBlockedBeforeMutationForOtherCoachsBooking() throws Exception {
        doThrow(new BusinessException(403, "只能操作本人授课的预约")).when(staff).requireBookingAccess(9L);
        mvc.perform(delete("/api/admin/bookings/9").header("Authorization", "Bearer COACH"))
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(post("/api/admin/bookings/9/manual-checkin").header("Authorization", "Bearer COACH"))
                .andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(bookings, logs);
    }
    @Test void validDelegateRequestReturnsNewBookingId() throws Exception {
        when(staff.create(any())).thenReturn(new BookingDtos.CreateSuccess(99L, 5));
        mvc.perform(post("/api/admin/bookings").header("Authorization", "Bearer COACH").contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\":1,\"packageId\":10,\"date\":\"2026-12-01\",\"startTime\":\"10:00\"}"))
                .andExpect(jsonPath("$.data.bookingId").value(99));
        verify(staff).create(new BookingDtos.StaffCreateRequest(1L, 10L, "2026-12-01", "10:00"));
    }
}
