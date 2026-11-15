package com.jianshenfang.dto;

import java.util.List;

public final class ReportDtos {
    private ReportDtos() {}

    public record BarItem(String label, double value) {}

    public record CoachRankItem(String name, int classes, int ratePercent) {}

    public record Dashboard(
            String studioName, String date,
            int todayMemberCount, int todayClassCount,
            int monthMemberCount, int monthClassCount,
            double todayRevenue, double monthRevenue,
            boolean showRevenue,
            List<Object> upcoming,
            List<BarItem> revenue7d,
            List<CoachRankItem> coachRank) {}

    public record Finance(
            double totalRevenue,
            double monthRevenue,
            List<BarItem> dailyRevenue,
            List<Object> topMembers) {}

    public record Courses(
            int totalConsumed,
            double cancelRate,
            List<BarItem> dailyConsumed,
            List<CoachRankItem> coachRank) {}

    /* ===== 课程统计：按课程名分组，再按教练细分 ===== */

    /** 单个课程下某个教练的统计 */
    public record CourseCoachStat(String coachName, int bookings, int classes, int canceled) {}

    /** 单个课程名的统计；classPercent = 该课程消课占全部消课的百分比 */
    public record CourseNameStat(String courseName, int bookings, int classes, int canceled,
                                 double classPercent, List<CourseCoachStat> coaches) {}

    public record CourseStats(String range, String from, String to,
                              int totalBookings, int totalClasses, int totalCanceled,
                              List<CourseNameStat> courses) {}
}
