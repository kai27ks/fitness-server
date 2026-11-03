package com.jianshenfang.controller;

import com.jianshenfang.auth.AuthContext;
import com.jianshenfang.common.Result;
import com.jianshenfang.dto.MemberDtos;
import com.jianshenfang.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/member")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @GetMapping("/home")
    public Result<MemberDtos.HomeResponse> home() {
        return Result.ok(memberService.home(AuthContext.userId()));
    }

    @GetMapping("/packages")
    public Result<List<MemberDtos.PackageInfo>> packages() {
        return Result.ok(memberService.myPackages(AuthContext.userId()));
    }

    @GetMapping("/me")
    public Result<MemberDtos.MeResponse> me() {
        return Result.ok(memberService.me(AuthContext.userId()));
    }
}
