package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.dto.ReportDtos;
import com.jianshenfang.entity.*;
import com.jianshenfang.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportService {

    private final BookingMapper bookingMapper;
    private final MemberPackageMapper packageMapper;
    private final CoachMapper coachMapper;
    private final MemberMapper memberMapper;
    private final CourseTemplateMapper courseMapper;

    @Value("${app.studio.name}") private String studioName;

    public ReportDtos.Dashboard dashboard(boolean showRevenue) {
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = today.atTime(23, 59, 59);
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();

        // 今日上课会员数 / 今日课节数
        List<Booking> todayBookings = bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                .ge(Booking::getStartTime, todayStart).le(Booking::getStartTime, todayEnd));
        int todayClassCount = todayBookings.size();
        int todayMemberCount = (int) todayBookings.stream().map(Booking::getMemberId).distinct().count();

        List<Booking> monthBookings = bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                .ge(Booking::getStartTime, monthStart));
        int monthClassCount = monthBookings.size();
        int monthMemberCount = (int) monthBookings.stream().map(Booking::getMemberId).distinct().count();

        double todayRevenue = showRevenue ? packageMapper.sumPrice(todayStart, todayEnd.plusSeconds(1)).doubleValue() : 0;
        double monthRevenue = showRevenue ? packageMapper.sumPrice(monthStart, LocalDateTime.now()).doubleValue() : 0;

        // 今日课程时间线（前 6 条）
        List<Object> upcoming = todayBookings.stream().sorted(Comparator.comparing(Booking::getStartTime)).limit(6)
                .map(b -> {
                    Member m = memberMapper.selectById(b.getMemberId());
                    CourseTemplate c = courseMapper.selectById(b.getCourseId());
                    Coach co = coachMapper.selectById(b.getCoachId());
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("time", b.getStartTime().format(DateTimeFormatter.ofPattern("HH:mm")));
                    map.put("courseName", c == null ? "课程" : c.getName());
                    map.put("coachName", co == null ? "教练" : co.getName());
                    map.put("memberName", m == null ? "会员" : m.getName());
                    map.put("status", b.getStatus());
                    return (Object) map;
                }).toList();

        // 近 7 日营收
        List<ReportDtos.BarItem> revenue7d = new ArrayList<>();
        if (showRevenue) {
            for (int i = 6; i >= 0; i--) {
                LocalDate d = today.minusDays(i);
                BigDecimal v = packageMapper.sumPrice(d.atStartOfDay(), d.plusDays(1).atStartOfDay());
                revenue7d.add(new ReportDtos.BarItem(d.format(DateTimeFormatter.ofPattern("MM-dd")), v.doubleValue()));
            }
        }

        return new ReportDtos.Dashboard(studioName, today.toString(), todayMemberCount, todayClassCount,
                monthMemberCount, monthClassCount, todayRevenue, monthRevenue, showRevenue, upcoming, revenue7d, coachRank(monthBookings));
    }

    public ReportDtos.Finance finance() {
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        double totalRevenue = packageMapper.sumPrice(LocalDate.of(2000, 1, 1).atStartOfDay(), LocalDateTime.now()).doubleValue();
        double monthRevenue = packageMapper.sumPrice(monthStart, LocalDateTime.now()).doubleValue();

        List<ReportDtos.BarItem> daily = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 6; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            BigDecimal v = packageMapper.sumPrice(d.atStartOfDay(), d.plusDays(1).atStartOfDay());
            daily.add(new ReportDtos.BarItem(d.format(DateTimeFormatter.ofPattern("MM-dd")), v.doubleValue()));
        }

        List<Object> topMembers = packageMapper.selectList(new LambdaQueryWrapper<MemberPackage>()
                .ge(MemberPackage::getCreateTime, monthStart))
                .stream().collect(Collectors.groupingBy(MemberPackage::getMemberId, Collectors.summingDouble(p -> p.getTotalPrice().doubleValue())))
                .entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).limit(5)
                .map(e -> {
                    Member m = memberMapper.selectById(e.getKey());
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", m == null ? "会员" : m.getName());
                    map.put("amount", round(e.getValue()));
                    return (Object) map;
                }).toList();

        return new ReportDtos.Finance(totalRevenue, monthRevenue, daily, topMembers);
    }

    public ReportDtos.Courses courses() {
        List<Booking> all = bookingMapper.selectList(new LambdaQueryWrapper<Booking>());
        int total = all.size();
        long canceled = all.stream().filter(b -> "CANCELED".equals(b.getStatus())).count();
        double cancelRate = total == 0 ? 0 : round(canceled * 100.0 / total);

        int consumed = all.stream().filter(b -> "CHECKED_IN".equals(b.getStatus()) || "COMPLETED".equals(b.getStatus())).mapToInt(x -> 1).sum();

        List<ReportDtos.BarItem> daily = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 6; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            long c = all.stream().filter(b -> !b.getStartTime().toLocalDate().isBefore(d) && b.getStartTime().toLocalDate().isBefore(d.plusDays(1))
                    && ("CHECKED_IN".equals(b.getStatus()) || "COMPLETED".equals(b.getStatus()))).count();
            daily.add(new ReportDtos.BarItem(d.format(DateTimeFormatter.ofPattern("MM-dd")), c));
        }

        return new ReportDtos.Courses(consumed, cancelRate, daily, coachRank(all));
    }

    private List<ReportDtos.CoachRankItem> coachRank(List<Booking> bookings) {
        Map<Long, List<Booking>> byCoach = bookings.stream().collect(Collectors.groupingBy(Booking::getCoachId));
        List<ReportDtos.CoachRankItem> rank = new ArrayList<>();
        byCoach.forEach((coachId, list) -> {
            Coach coach = coachMapper.selectById(coachId);
            int all = list.size();
            int done = (int) list.stream().filter(b -> "CHECKED_IN".equals(b.getStatus()) || "COMPLETED".equals(b.getStatus())).count();
            int rate = all == 0 ? 0 : (int) Math.round(done * 100.0 / all);
            rank.add(new ReportDtos.CoachRankItem(coach == null ? "教练" : coach.getName(), done, rate));
        });
        rank.sort((a, b) -> Integer.compare(b.classes(), a.classes()));
        return rank;
    }

    private double round(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
