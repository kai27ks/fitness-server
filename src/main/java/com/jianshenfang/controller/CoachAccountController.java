package com.jianshenfang.controller;

import com.jianshenfang.auth.AuthUtil;
import com.jianshenfang.common.Result;
import com.jianshenfang.dto.CoachAccountDtos;
import com.jianshenfang.service.CoachAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/coaches")
@RequiredArgsConstructor
public class CoachAccountController {
    private final CoachAccountService accountService;

    @GetMapping("/accounts")
    public Result<List<CoachAccountDtos.Account>> list() {
        AuthUtil.requireAdmin();
        return Result.ok(accountService.list());
    }

    @GetMapping("/{id}/account")
    public Result<CoachAccountDtos.Account> get(@PathVariable Long id) {
        AuthUtil.requireAdmin();
        return Result.ok(accountService.get(id));
    }

    @PostMapping("/{id}/account")
    public Result<CoachAccountDtos.Account> configure(@PathVariable Long id,
                                                    @Valid @RequestBody CoachAccountDtos.Configure req) {
        AuthUtil.requireAdmin();
        return Result.ok(accountService.configure(id, req));
    }

    @PutMapping("/{id}/account/password")
    public Result<Void> resetPassword(@PathVariable Long id,
                                      @Valid @RequestBody CoachAccountDtos.ResetPassword req) {
        AuthUtil.requireAdmin();
        accountService.resetPassword(id, req.newPassword());
        return Result.ok();
    }
}
