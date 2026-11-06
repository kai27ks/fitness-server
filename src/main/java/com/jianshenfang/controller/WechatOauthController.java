package com.jianshenfang.controller;

import com.jianshenfang.common.BusinessException;
import com.jianshenfang.config.WxProperties;
import com.jianshenfang.service.WechatAuthService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 公众号入口与回调。路径不在 /api/** 下，不受 AuthInterceptor 保护。
 * 公众号自定义菜单 / 二维码应指向 /wechat/oauth/entry，而不是直接指向 H5 页面。
 */
@RestController
@RequiredArgsConstructor
public class WechatOauthController {

    private final WechatAuthService wechatAuthService;
    private final WxProperties wx;

    @GetMapping("/wechat/oauth/entry")
    public void entry(@RequestParam(required = false) String redirect,
                      @RequestParam(required = false) String mockOpenid,
                      HttpServletResponse response) throws IOException {
        try {
            String target = wx.isMock()
                    ? wechatAuthService.finish(wechatAuthService.mockIdentity(mockOpenid), redirect)
                    : wechatAuthService.buildAuthorizeUrl(redirect);
            response.sendRedirect(target);
        } catch (BusinessException e) {
            response.sendRedirect(wechatAuthService.errorUrl(e.getMessage()));
        }
    }

    @GetMapping("/wechat/oauth/callback")
    public void callback(@RequestParam(required = false) String code,
                         @RequestParam(required = false) String state,
                         HttpServletResponse response) throws IOException {
        try {
            response.sendRedirect(wechatAuthService.handleCallback(code, state));
        } catch (BusinessException e) {
            response.sendRedirect(wechatAuthService.errorUrl(e.getMessage()));
        }
    }
}
