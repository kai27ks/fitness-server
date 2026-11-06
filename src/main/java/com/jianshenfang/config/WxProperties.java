package com.jianshenfang.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 公众号网页授权配置。生产用环境变量 WX_APPID / WX_SECRET 覆盖，勿写死在仓库里。
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
}
