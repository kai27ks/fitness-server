package com.jianshenfang.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 会员风采配置（app.moments.*），改 yml 即可生效，无需改代码。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.moments")
public class MomentProperties {

    /** 风采功能总开关：关闭后所有风采接口不可用，与下面两个审核开关无关 */
    private boolean enabled = true;

    /** 开关① 机器审核：开启后每张图片提交时调用腾讯云 IMS；关闭则完全跳过，不发 HTTP */
    private boolean machineAuditEnabled = false;

    /** 开关② 人工审核：开启后图片进入待审核队列；关闭则不入队 */
    private boolean needReview = true;

    private String uploadDir = "./upload/moments";
    private String urlPrefix = "/upload/moments";
    private long maxSourceBytes = 20 * 1024 * 1024L;
    private long maxSourcePixels = 40_000_000L;
    private int maxEdge = 1920;
    private int minEdge = 1280;
    private int thumbEdge = 640;
    private long thumbMaxBytes = 150 * 1024L;
    private double jpegQuality = 0.82;
    private double minJpegQuality = 0.70;
    private long maxOutputBytes = 1024 * 1024L;
    private int dailyLimit = 4;
    private int titleMaxLength = 200;
    private int commentMaxLength = 200;
    private int commentMaxPerMinute = 10;

    /** 腾讯云图片内容安全（天御 IMS）：纯 API 连接参数，机审开关见上面的 machineAuditEnabled */
    private Audit audit = new Audit();

    /**
     * 机审是否真正开启：顶层开关或旧配置别名任一为 true 即开启，
     * 保证已有 application.yml / 环境变量不改动也能继续生效。
     */
    public boolean machineAuditOn() {
        return machineAuditEnabled || (audit != null && audit.isEnabled());
    }

    @Data
    public static class Audit {
        /** 兼容别名：旧版机审开关；新配置请改用顶层的 machine-audit-enabled */
        private boolean enabled = false;
        private String endpoint = "ims.tencentcloudapi.com";
        private String region = "ap-guangzhou";
        private String version = "2020-12-29";
        private String action = "ImageModeration";
        private String secretId = "";
        private String secretKey = "";
        private String bizType = "";
        private int timeoutMs = 5000;
        /** 机审异常时放行转人工；false 则拒绝上传 */
        private boolean failOpen = true;
        /** Suggestion=Review 时是否直接打回 */
        private boolean reviewAsReject = false;
    }
}
