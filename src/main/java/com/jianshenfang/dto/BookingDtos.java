package com.jianshenfang.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class BookingDtos {
    private BookingDtos() {}

    public record CreateRequest(
            @NotNull(message = "课包不能为空") Long packageId,
            @NotNull(message = "课程不能为空") Long courseId,
            @NotBlank(message = "日期不能为空") String date,
            @NotBlank(message = "时间不能为空") String startTime) {}

    public record CancelRequest(String reason) {}

    /** 时段状态：FREE 可约 / BOOKED 已被他人约 / MINE 我的预约 / LEAVE 教练请假 / PAST 已过时间 */
    public record Slot(String start, String end, String status) {}

    public record SlotsResponse(String date, int durationMin, int remainSessions, List<Slot> slots) {}

    public record BookingVO(Long id, String courseName, String coachName,
                            String startTime, String endTime, String status,
                            String cancelBy, String cancelReason, boolean cancellable) {}

    public record CreateSuccess(Long bookingId, int remainSessions) {}

    /** 管理端预约列表项：含会员名 */
    public record AdminBookingVO(Long id, String memberName, String courseName, String coachName,
                                 String startTime, String endTime, String status,
                                 String cancelBy, String cancelReason) {}
}
