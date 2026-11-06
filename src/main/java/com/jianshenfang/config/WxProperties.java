package com.jianshenfang.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 公众号网页授权 / 模板消息配置。生产用环境变量 WX_APPID / WX_SECRET 覆盖，勿写死在仓库里。
 */
@Data
@Component
@ConfigurationProperties(prefix = "wx")
public class WxProperties {
    private String appid = "";
    private String secret = "";
    private String scope = "snsapi_userinfo";
    /** 微信回调可达的后端域名，需与公众号后台「网页授权域名」一致 */
    private String callbackBaseUrl = "http://localhost:8080";
    /** 会员端 H5 域名，回调完成后 302 到这里 */
    private String h5BaseUrl = "http://localhost:5173";
    /** dev 本地联调：跳过微信服务器，直接模拟 openid */
    private boolean mock = false;
    private int stateTtlSeconds = 300;
    private int ticketTtlSeconds = 120;
    private int claimTicketTtlSeconds = 600;
    private int claimCodeExpireDays = 7;
    private int claimCodeMaxFail = 5;
    private String avatarUploadDir = "./upload/avatars";
    private String avatarUrlPrefix = "/upload/avatars";

    /* ===== 预约通知（Q9~Q11） ===== */
    /** 事件推送验签 Token，与公众号后台「服务器配置」一致 */
    private String token = "";
    /** 公众号二维码图片地址，用于引导关注页；为空时只显示文字引导 */
    private String qrcodeUrl = "";
    /** 模板消息总开关；模板 ID 未配置时自动跳过，不发请求 */
    private boolean notifyEnabled = true;
    /** scene -> 模板 ID：BOOKING_CREATED / BOOKING_CANCELED / BOOKING_REMINDER */
    private Map<String, String> templates = new HashMap<>();
    /** 上课前提前多少小时提醒（Q11） */
    private int reminderHours = 24;
    /** 扫描窗口宽度（分钟）；配合 dedup_key 保证只发一次 */
    private int reminderWindowMinutes = 60;
    /** dev：强制显示引导关注页，便于联调 Q10 */
    private boolean mockFollowRequired = false;
}
