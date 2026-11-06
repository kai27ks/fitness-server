package com.jianshenfang.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.entity.WechatAccount;
import com.jianshenfang.mapper.WechatAccountMapper;
import com.jianshenfang.service.WxApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公众号服务器配置校验与关注/取关事件推送。
 * 用于在用户关注或取关后实时维护 wechat_account.subscribed，
 * 避免只靠授权回调查一次导致「取关后仍尝试发模板消息」。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class WechatEventController {

    private final WxApiClient wxApiClient;
    private final WechatAccountMapper accountMapper;

    /** 公众号后台配置服务器 URL 时的 GET 校验 */
    @GetMapping(value = "/wechat/event", produces = "text/plain;charset=UTF-8")
    public String verify(@RequestParam(required = false) String signature,
                         @RequestParam(required = false) String timestamp,
                         @RequestParam(required = false) String nonce,
                         @RequestParam(required = false) String echostr) {
        return wxApiClient.verify(signature, timestamp, nonce) ? (echostr == null ? "" : echostr) : "invalid";
    }

    @PostMapping(value = "/wechat/event", produces = "text/plain;charset=UTF-8")
    public String event(@RequestBody(required = false) String xml,
                        @RequestParam(required = false) String signature,
                        @RequestParam(required = false) String timestamp,
                        @RequestParam(required = false) String nonce) {
        if (!wxApiClient.verify(signature, timestamp, nonce)) return "invalid";
        String openid = extract(xml, "FromUserName");
        String event = extract(xml, "Event");
        if (openid != null && ("subscribe".equalsIgnoreCase(event) || "unsubscribe".equalsIgnoreCase(event))) {
            WechatAccount account = accountMapper.selectOne(new LambdaQueryWrapper<WechatAccount>()
                    .eq(WechatAccount::getOpenid, openid));
            if (account != null) {
                WechatAccount upd = new WechatAccount();
                upd.setId(account.getId());
                LocalDateTime now = LocalDateTime.now();
                if ("subscribe".equalsIgnoreCase(event)) {
                    upd.setSubscribed(1);
                    upd.setSubscribedAt(now);
                } else {
                    upd.setSubscribed(0);
                    upd.setUnsubscribedAt(now);
                }
                accountMapper.updateById(upd);
                log.info("微信 {} 事件 openid 尾号={}", event,
                        openid.length() > 6 ? openid.substring(openid.length() - 6) : "***");
            }
        }
        return "success";
    }

    /** 用正则取 CDATA 字段，避免引入 XML 解析器带来的 XXE 风险 */
    private String extract(String xml, String tag) {
        if (xml == null) return null;
        Matcher m = Pattern.compile(
                "<" + tag + ">(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</" + tag + ">",
                Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }
}
