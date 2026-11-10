package com.jianshenfang.controller;

import com.jianshenfang.auth.AuthContext;
import com.jianshenfang.auth.AuthInterceptor;
import com.jianshenfang.auth.JwtUtil;
import com.jianshenfang.config.SupportProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SupportControllerTest {
    private SupportProperties properties;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        properties = new SupportProperties();
        properties.setPhone(" 15805080815 ");
        properties.setEmail(" kai27ks@163.com ");
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.parse(anyString())).thenAnswer(call -> new JwtUtil.AuthUser(1L, call.getArgument(0)));
        when(jwt.parse("invalid")).thenThrow(new IllegalArgumentException("Invalid token"));
        mvc = MockMvcBuilders.standaloneSetup(new SupportController(properties))
                .addInterceptors(new AuthInterceptor(jwt)).build();
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEMBER", "COACH"})
    void eligibleRolesReceiveConfiguredContact(String role) throws Exception {
        mvc.perform(get("/api/support").header("Authorization", "Bearer " + role))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.title").value("系统维护与技术支持"))
                .andExpect(jsonPath("$.data.phone").value("15805080815"))
                .andExpect(jsonPath("$.data.email").value("kai27ks@163.com"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "OTHER"})
    void otherRolesDoNotReceiveContact(String role) throws Exception {
        mvc.perform(get("/api/support").header("Authorization", "Bearer " + role))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void requiresValidLogin() throws Exception {
        mvc.perform(get("/api/support")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/support").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledContactIsHidden() throws Exception {
        properties.setEnabled(false);
        mvc.perform(get("/api/support").header("Authorization", "Bearer MEMBER"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void missingContactIsHidden() throws Exception {
        properties.setPhone(" ");
        properties.setEmail(null);
        mvc.perform(get("/api/support").header("Authorization", "Bearer COACH"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void allowsOneContactAndCustomCopy() throws Exception {
        properties.setPhone(null);
        properties.setTitle("  开发维护  ");
        properties.setDescription("  欢迎反馈使用问题  ");
        properties.setDeveloperName("  技术支持团队  ");
        mvc.perform(get("/api/support").header("Authorization", "Bearer COACH"))
                .andExpect(jsonPath("$.data.title").value("开发维护"))
                .andExpect(jsonPath("$.data.description").value("欢迎反馈使用问题"))
                .andExpect(jsonPath("$.data.developerName").value("技术支持团队"))
                .andExpect(jsonPath("$.data.phone").value(""))
                .andExpect(jsonPath("$.data.email").value("kai27ks@163.com"));
    }
}
