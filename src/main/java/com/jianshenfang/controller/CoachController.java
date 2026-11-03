package com.jianshenfang.controller;

import com.jianshenfang.auth.AuthContext;
import com.jianshenfang.common.Result;
import com.jianshenfang.dto.BookingDtos;
import com.jianshenfang.dto.MemberDtos;
import com.jianshenfang.service.CoachService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/coaches")
@RequiredArgsConstructor
public class CoachController {

    private final CoachService coachService;

    @GetMapping
    public Result<List<MemberDtos.CoachBrief>> list() {
        return Result.ok(coachService.list());
    }

    @GetMapping("/{id}")
    public Result<CoachService.CoachDetail> detail(@PathVariable Long id) {
        return Result.ok(coachService.detail(id));
    }

    /** 约课时段网格：FREE/BOOKED/MINE/LEAVE/PAST */
    @GetMapping("/{id}/slots")
    public Result<BookingDtos.SlotsResponse> slots(@PathVariable Long id,
                                                   @RequestParam String date,
                                                   @RequestParam Long courseId) {
        return Result.ok(coachService.slots(id, date, courseId, AuthContext.userId()));
    }
}
