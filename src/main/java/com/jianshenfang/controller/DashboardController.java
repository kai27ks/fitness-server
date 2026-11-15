package com.jianshenfang.controller;

import com.jianshenfang.auth.AuthContext;
import com.jianshenfang.auth.AuthUtil;
import com.jianshenfang.common.Result;
import com.jianshenfang.dto.ReportDtos;
import com.jianshenfang.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DashboardController {

    private final ReportService reportService;

    @GetMapping("/admin/dashboard")
    public Result<?> dashboard() {
        AuthUtil.requireStaff();
        boolean showRevenue = "ADMIN".equals(AuthContext.role());
        return Result.ok(reportService.dashboard(showRevenue));
    }

    @GetMapping("/reports/finance")
    public Result<ReportDtos.Finance> finance() {
        AuthUtil.requireAdmin();
        return Result.ok(reportService.finance());
    }

    @GetMapping("/reports/courses")
    public Result<ReportDtos.Courses> courses() {
        AuthUtil.requireAdmin();
        return Result.ok(reportService.courses());
    }

    /** 课程统计：range = total / month / week，按课程名分组并按教练细分 */
    @GetMapping("/reports/course-stats")
    public Result<ReportDtos.CourseStats> courseStats(@RequestParam(defaultValue = "total") String range) {
        AuthUtil.requireAdmin();
        return Result.ok(reportService.courseStats(range));
    }
}
