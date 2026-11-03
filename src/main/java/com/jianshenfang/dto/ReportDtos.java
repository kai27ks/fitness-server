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
}
