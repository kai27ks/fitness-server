package com.jianshenfang.controller;

import com.jianshenfang.auth.AuthContext;
import com.jianshenfang.auth.AuthUtil;
import com.jianshenfang.common.Result;
import com.jianshenfang.dto.AdminDtos;
import com.jianshenfang.dto.BookingDtos;
import com.jianshenfang.service.BookingService;
import com.jianshenfang.service.CoachAdminService;
import com.jianshenfang.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/bookings")
@RequiredArgsConstructor
public class BookingAdminController {

    private final BookingService bookingService;
    private final CoachAdminService coachService;
    private final OperationLogService logService;

    @GetMapping
    public Result<?> list(@RequestParam(required = false) Long coachId,
                          @RequestParam(required = false) Long memberId,
                          @RequestParam(required = false) String status,
                          @RequestParam(required = false) String date,
                          @RequestParam(required = false) String startDate,
                          @RequestParam(required = false) String endDate) {
        AuthUtil.requireStaff();
        // 教练仅看名下；管理员可看全部并按条件筛选
        Long effectiveCoach = "COACH".equals(AuthContext.role()) ? coachService.currentCoach().getId() : coachId;
        AdminDtos.BookingQuery q = new AdminDtos.BookingQuery(effectiveCoach, memberId, status, date, startDate, endDate);
        List<BookingDtos.AdminBookingVO> data = bookingService.adminList(null, q);
        return Result.ok(data);
    }

    @DeleteMapping("/{id}")
    public Result<Void> cancel(@PathVariable Long id, @RequestBody(required = false) AdminDtos.CancelRequest req) {
        AuthUtil.requireStaff();
        String reason = req == null ? null : req.reason();
        bookingService.staffCancel(AuthContext.userId(), id, reason);
        logService.log("CANCEL_BOOKING", "BOOKING", id, reason);
        return Result.ok();
    }

    @PostMapping("/{id}/manual-checkin")
    public Result<Void> manualCheckIn(@PathVariable Long id) {
        AuthUtil.requireStaff();
        bookingService.manualCheckIn(id);
        logService.log("MANUAL_CHECKIN", "BOOKING", id, "管理端手动补签");
        return Result.ok();
    }
}
