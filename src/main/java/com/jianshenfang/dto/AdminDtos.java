package com.jianshenfang.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class AdminDtos {
    private AdminDtos() {}

    /* ===== 会员 ===== */
    public record MemberCreate(
            @NotBlank(message = "姓名必填") String name,
            @NotBlank(message = "手机号必填") String phone,
            Integer gender,
            String birthday,
            Long coachId,
            String password) {}

    public record MemberUpdate(String name, Integer gender, String birthday, Long coachId) {}

    public record BindCoach(Long coachId, String remark) {}

    public record PackageAdd(
            @NotNull(message = "课程必选") Long courseId,
            @NotNull(message = "总节数必填") Integer totalSessions,
            @NotNull(message = "总价必填") BigDecimal totalPrice,
            String startDate,
            String expireDate) {}

    public record AdjustSessions(Long delta, String remark) {}

    public record MemberDetail(
            Object member, List<Object> packages, List<Object> bookings, Object coach) {}

    /* ===== 课程模板 ===== */
    public record CourseCreate(
            @NotBlank(message = "课程名称必填") String name,
            @NotNull(message = "时长必填") Integer durationMin,
            Integer defaultSessions) {}

    public record CourseUpdate(String name, Integer durationMin, Integer defaultSessions, Integer status) {}

    /* ===== 教练 ===== */
    public record CoachCreate(
            @NotBlank(message = "姓名必填") String name,
            String phone,
            String intro,
            Integer status) {}

    public record CoachUpdate(String name, String intro, String photo, Integer status) {}

    public record QualificationAdd(
            @NotBlank(message = "资质名称必填") String name,
            @NotBlank(message = "证书编号必填") String certNo,
            String issuer,
            String image,
            String expireDate) {}

    public record LeaveCreate(
            @NotNull(message = "开始时间必填") String startTime,
            @NotNull(message = "结束时间必填") String endTime,
            String reason,
            Integer type) {}

    /* ===== 预约（管理端） ===== */
    public record BookingCreateAdmin(
            @NotNull Long memberId,
            @NotNull Long courseId,
            @NotNull Long coachId,
            @NotNull Long packageId,
            @NotBlank String date,
            @NotBlank String startTime) {}

    public record BookingQuery(Long coachId, Long memberId, String status, String date, String startDate, String endDate) {}

    public record CancelRequest(String reason) {}
}
