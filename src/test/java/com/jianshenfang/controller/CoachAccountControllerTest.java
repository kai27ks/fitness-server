package com.jianshenfang.controller;

import com.jianshenfang.auth.AuthContext;
import com.jianshenfang.auth.AuthInterceptor;
import com.jianshenfang.auth.JwtUtil;
import com.jianshenfang.common.GlobalExceptionHandler;
import com.jianshenfang.dto.CoachAccountDtos;
import com.jianshenfang.service.CoachAccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CoachAccountControllerTest {
    private CoachAccountService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(CoachAccountService.class);
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.parse(anyString())).thenAnswer(call -> new JwtUtil.AuthUser(1L, call.getArgument(0)));
        mvc = MockMvcBuilders.standaloneSetup(new CoachAccountController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(new AuthInterceptor(jwt)).build();
    }

    @AfterEach
    void clearContext() { AuthContext.clear(); }

    @Test
    void requiresLogin() throws Exception {
        mvc.perform(get("/api/admin/coaches/3/account")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COACH", "MEMBER"})
    void accountOperationsAreAdminOnly(String role) throws Exception {
        String token = "Bearer " + role;
        mvc.perform(get("/api/admin/coaches/accounts").header("Authorization", token)).andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/admin/coaches/3/account").header("Authorization", token)).andExpect(jsonPath("$.code").value(400));
        mvc.perform(post("/api/admin/coaches/3/account").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"BIND\",\"phone\":\"13800138000\"}")).andExpect(jsonPath("$.code").value(400));
        mvc.perform(put("/api/admin/coaches/3/account/password").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"Replacement123!\"}")).andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(service);
    }

    @Test
    void accountResponseContainsNoPassword() throws Exception {
        when(service.get(3L)).thenReturn(new CoachAccountDtos.Account(3L, 20L, "13800138000", "COACH", 1, "BOUND"));
        mvc.perform(get("/api/admin/coaches/3/account").header("Authorization", "Bearer ADMIN"))
                .andExpect(jsonPath("$.data.phone").value("13800138000"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    void rejectsInvalidInputBeforeAccountMutation() throws Exception {
        mvc.perform(post("/api/admin/coaches/3/account").header("Authorization", "Bearer ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"OTHER\",\"phone\":\"13800138000\"}")).andExpect(jsonPath("$.code").value(400));
        mvc.perform(post("/api/admin/coaches/3/account").header("Authorization", "Bearer ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"CREATE\",\"phone\":\"123\"}")).andExpect(jsonPath("$.code").value(400));
        mvc.perform(put("/api/admin/coaches/3/account/password").header("Authorization", "Bearer ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"123456\"}")).andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(service);
    }
}
