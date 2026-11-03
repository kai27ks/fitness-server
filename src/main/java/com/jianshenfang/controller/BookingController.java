package com.jianshenfang.controller;

import com.jianshenfang.auth.AuthContext;
import com.jianshenfang.common.Result;
import com.jianshenfang.dto.BookingDtos;
import com.jianshenfang.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @PostMapping
    public Result<BookingDtos.CreateSuccess> create(@Valid @RequestBody BookingDtos.CreateRequest req) {
        return Result.ok(bookingService.create(AuthContext.userId(), req));
    }

    @GetMapping("/my")
    public Result<List<BookingDtos.BookingVO>> my(@RequestParam(required = false) String tab) {
        return Result.ok(bookingService.myBookings(AuthContext.userId(), tab));
    }

    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id, @RequestBody(required = false) BookingDtos.CancelRequest req) {
        bookingService.cancel(AuthContext.userId(), id, req == null ? null : req.reason());
        return Result.ok();
    }
}
