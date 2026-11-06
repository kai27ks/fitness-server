package com.jianshenfang.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianshenfang.config.WxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;

/**
 * 公众号服务端 API：全局 access_token（与网页授权 token 是两个东西）、用户信息、模板消息、事件验签。
 * 全局 token 内存缓存 2 小时并提前刷新；多实例部署时需换成 Redis/DB 共享缓存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxApiClient {

    private final WxProperties wx;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = buildRestTemplate();

    private volatile String cachedToken;
    private volatile LocalDateTime tokenExpireAt;

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }

    /** 全局 access_token，mock 模式或未配置密钥时返回 null（调用方跳过发送） */
    public synchronized String accessToken() {
        if (wx.isMock() || wx.getAppid().isBlank() || wx.getSecret().isBlank()) return null;
        if (cachedToken != null && tokenExpireAt != null
                && tokenExpireAt.isAfter(LocalDateTime.now().plusMinutes(2))) {
            return cachedToken;
        }
        String url = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential"
                + "&appid=" + enc(wx.getAppid()) + "&secret=" + enc(wx.getSecret());
        JsonNode r = get(url);
        if (r == null || r.has("errcode") || !r.has("access_token")) {
            log.error("获取全局 access_token 失败: {}", r);
            return null;
        }
        cachedToken = r.path("access_token").asText();
        int expires = Math.max(60, r.path("expires_in").asInt(7200) - 300);
        tokenExpireAt = LocalDateTime.now().plusSeconds(expires);
        return cachedToken;
    }

    public void clearToken() {
        cachedToken = null;
        tokenExpireAt = null;
    }

    /** 查询用户是否关注服务号；null 表示查询失败（不据此改库） */
    public Boolean subscribed(String openid) {
        String token = accessToken();
        if (token == null) return null;
        JsonNode r = get("https://api.weixin.qq.com/cgi-bin/user/info?access_token="
                + enc(token) + "&openid=" + enc(openid) + "&lang=zh_CN");
        if (r == null || r.has("errcode")) return null;
        return r.path("subscribe").asInt(0) == 1;
    }

    /** 发送模板消息；返回微信原始响应，null 表示本地异常/未配置 */
    public JsonNode sendTemplate(String openid, String templateId, String url, Map<String, String> data) {
        String token = accessToken();
        if (token == null) return null;
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("touser", openid);
            body.put("template_id", templateId);
            if (url != null && !url.isBlank()) body.put("url", url);
            ObjectNode dataNode = body.putObject("data");
            data.forEach((k, v) -> dataNode.putObject(k).put("value", v == null ? "" : v));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(body.toString(), headers);
            String resp = restTemplate.postForObject(
                    "https://api.weixin.qq.com/cgi-bin/message/template/send?access_token=" + enc(token),
                    entity, String.class);
            return resp == null ? null : objectMapper.readTree(resp);
        } catch (Exception e) {
            log.warn("模板消息发送异常: {}", e.getMessage());
            return null;
        }
    }

    /** 公众号服务器配置校验 / 事件推送验签 */
    public boolean verify(String signature, String timestamp, String nonce) {
        if (wx.getToken() == null || wx.getToken().isBlank()) return false;
        if (signature == null || timestamp == null || nonce == null) return false;
        String[] arr = {wx.getToken(), timestamp, nonce};
        Arrays.sort(arr);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest((arr[0] + arr[1] + arr[2]).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString().equalsIgnoreCase(signature);
        } catch (Exception e) {
            return false;
        }
    }

    private JsonNode get(String url) {
        try {
            String body = restTemplate.getForObject(url, String.class);
            return body == null ? null : objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("微信接口调用失败: {}", e.getMessage());
            return null;
        }
    }

    private String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
