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

    /** 管理端课包列表项 */
    public record PackageAdminVO(Long id, Long memberId, String memberName, String courseName,
                                 int remainSessions, int totalSessions, String totalPrice,
                                 String expireDate, String status) {}

    /** 会员详情内的课包项（带课程名称） */
    public record MemberPackageVO(Long id, Long courseId, String courseName,
                                  int remainSessions, int totalSessions, String totalPrice,
                                  String expireDate, String status) {}

    /** 会员列表项：带专属教练名、课程（多课程多条）、剩余课时与临期状态 */
    public record MemberAdminVO(Long id, String name, String phone, Integer gender, String birthday,
                                Long coachId, String coachName,
                                int remainSessions, int totalSessions,
                                List<MemberCourseVO> courses, String status) {}

    public record MemberCourseVO(Long packageId, Long courseId, String courseName,
                                 int remainSessions, int totalSessions, String expireDate) {}

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
