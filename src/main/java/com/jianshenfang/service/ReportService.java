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
import java.time.DayOfWeek;
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

    /**
     * 课程统计：按课程名称分组，可切换「当日 / 本周 / 本月 / 总计」，组内再按教练细分。
     *
     * <p>口径：以 booking 为准，classes = 已消课（CHECKED_IN / COMPLETED），bookings = 预约总数（含取消），
     * canceled = 取消数；按 classes 从高到低排序，课程名与教练名取自主档表，取不到时降级为「未知课程 / 未知教练」。
     */
    public ReportDtos.CourseStats courseStats(String range) {
        String r = (range == null || range.isBlank()) ? "total" : range.trim().toLowerCase();
        if (!"day".equals(r) && !"month".equals(r) && !"week".equals(r)) r = "total";

        LocalDate today = LocalDate.now();
        LocalDateTime from = null;
        LocalDateTime to = null;
        if ("day".equals(r)) {
            from = today.atStartOfDay();
            to = today.atTime(23, 59, 59);
        } else if ("week".equals(r)) {
            LocalDate monday = today.with(DayOfWeek.MONDAY);
            from = monday.atStartOfDay();
            to = monday.plusDays(6).atTime(23, 59, 59);
        } else if ("month".equals(r)) {
            LocalDate first = today.withDayOfMonth(1);
            from = first.atStartOfDay();
            to = first.withDayOfMonth(first.lengthOfMonth()).atTime(23, 59, 59);
        }

        LambdaQueryWrapper<Booking> w = new LambdaQueryWrapper<>();
        if (from != null) w.ge(Booking::getStartTime, from);
        if (to != null) w.le(Booking::getStartTime, to);
        List<Booking> all = bookingMapper.selectList(w);

        Map<Long, String> courseNames = courseMapper.selectList(null).stream()
                .collect(Collectors.toMap(CourseTemplate::getId,
                        c -> c.getName() == null ? "未命名课程" : c.getName(), (a, b) -> a));
        Map<Long, String> coachNames = coachMapper.selectList(null).stream()
                .collect(Collectors.toMap(Coach::getId,
                        c -> c.getName() == null ? "未命名教练" : c.getName(), (a, b) -> a));

        int totalClasses = (int) all.stream().filter(ReportService::consumed).count();
        int totalCanceled = (int) all.stream().filter(b -> Booking.CANCELED.equals(b.getStatus())).count();

        List<ReportDtos.CourseNameStat> courses = new ArrayList<>();
        Map<String, List<Booking>> byCourse = all.stream()
                .collect(Collectors.groupingBy(b -> courseNames.getOrDefault(b.getCourseId(), "未知课程")));
        for (Map.Entry<String, List<Booking>> e : byCourse.entrySet()) {
            List<Booking> items = e.getValue();
            int classes = (int) items.stream().filter(ReportService::consumed).count();
            int canceled = (int) items.stream().filter(b -> Booking.CANCELED.equals(b.getStatus())).count();

            List<ReportDtos.CourseCoachStat> coaches = new ArrayList<>();
            Map<String, List<Booking>> byCoach = items.stream()
                    .collect(Collectors.groupingBy(b -> coachNames.getOrDefault(b.getCoachId(), "未知教练")));
            for (Map.Entry<String, List<Booking>> ce : byCoach.entrySet()) {
                List<Booking> cs = ce.getValue();
                coaches.add(new ReportDtos.CourseCoachStat(ce.getKey(), cs.size(),
                        (int) cs.stream().filter(ReportService::consumed).count(),
                        (int) cs.stream().filter(b -> Booking.CANCELED.equals(b.getStatus())).count()));
            }
            Comparator<ReportDtos.CourseCoachStat> cc = Comparator
                    .comparingInt(ReportDtos.CourseCoachStat::classes).reversed();
            coaches.sort(cc.thenComparing(Comparator.comparingInt(ReportDtos.CourseCoachStat::bookings).reversed())
                    .thenComparing(ReportDtos.CourseCoachStat::coachName));

            courses.add(new ReportDtos.CourseNameStat(e.getKey(), items.size(), classes, canceled,
                    totalClasses == 0 ? 0 : round(classes * 100.0 / totalClasses), coaches));
        }
        Comparator<ReportDtos.CourseNameStat> nc = Comparator
                .comparingInt(ReportDtos.CourseNameStat::classes).reversed();
        courses.sort(nc.thenComparing(Comparator.comparingInt(ReportDtos.CourseNameStat::bookings).reversed())
                .thenComparing(ReportDtos.CourseNameStat::courseName));

        return new ReportDtos.CourseStats(r,
                from == null ? "全部" : from.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                to == null ? "现在" : to.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                all.size(), totalClasses, totalCanceled, courses);
    }

    private static boolean consumed(Booking b) {
        return Booking.CHECKED_IN.equals(b.getStatus()) || Booking.COMPLETED.equals(b.getStatus());
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
