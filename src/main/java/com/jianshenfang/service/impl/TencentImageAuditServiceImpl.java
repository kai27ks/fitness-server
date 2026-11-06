package com.jianshenfang.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianshenfang.config.MomentProperties;
import com.jianshenfang.service.ImageAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 腾讯云图片内容安全（天御 IMS）ImageModeration 接入。
 *
 * <p>不引入腾讯云 SDK，直接用 JDK HttpClient + TC3-HMAC-SHA256 签名，依赖最轻、便于替换服务商。
 * 是否调用由顶层开关 app.moments.machine-audit-enabled 决定；app.moments.audit.* 只放连接参数。
 * 开关开启但密钥未配置时降级为 SKIPPED 并输出一次性告警，不阻断上传，
 * 后续由「人工审核」开关决定进入待审队列还是立即展示。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TencentImageAuditServiceImpl implements ImageAuditService {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final DateTimeFormatter UTC_DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final MomentProperties properties;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** 密钥缺失只告警一次，避免每张图片重复刷屏 */
    private final AtomicBoolean credentialsWarned = new AtomicBoolean(false);

    @Override
    public boolean enabled() {
        return properties.machineAuditOn();
    }

    @Override
    public AuditResult audit(byte[] jpegBytes, String dataId) {
        if (!enabled()) return AuditResult.skipped();

        MomentProperties.Audit c = properties.getAudit();
        // yml 里留空或环境变量未注入时，Binder 可能给 null，统一按「未配置」处理
        String secretId = c.getSecretId() == null ? "" : c.getSecretId();
        String secretKey = c.getSecretKey() == null ? "" : c.getSecretKey();
        if (secretId.isBlank() || secretKey.isBlank()) {
            if (credentialsWarned.compareAndSet(false, true)) {
                log.warn("已开启机器审核但未配置腾讯云 SecretId/SecretKey，机审降级跳过；"
                        + "请配置 app.moments.audit.secret-id / secret-key，或关闭 app.moments.machine-audit-enabled");
            }
            return AuditResult.skipped();
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            if (c.getBizType() != null && !c.getBizType().isBlank()) body.put("BizType", c.getBizType());
            body.put("DataId", dataId == null ? "" : dataId);
            body.put("FileContent", Base64.getEncoder().encodeToString(jpegBytes));
            String payload = objectMapper.writeValueAsString(body);

            long ts = System.currentTimeMillis() / 1000;
            String authorization = sign(c, payload, ts);
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://" + c.getEndpoint()))
                    .timeout(Duration.ofMillis(c.getTimeoutMs()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", authorization)
                    .header("X-TC-Action", c.getAction())
                    .header("X-TC-Version", c.getVersion())
                    .header("X-TC-Timestamp", String.valueOf(ts))
                    .header("X-TC-Region", c.getRegion())
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return AuditResult.error("图片审核服务返回 HTTP " + response.statusCode());
            }
            return parse(c, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AuditResult.error("图片审核被中断");
        } catch (Exception e) {
            log.warn("图片机审调用失败 dataId={}: {}", dataId, e.toString());
            return AuditResult.error("图片审核服务暂时不可用");
        }
    }

    private AuditResult parse(MomentProperties.Audit c, String body) throws Exception {
        JsonNode resp = objectMapper.readTree(body).path("Response");
        String requestId = resp.path("RequestId").asText("");
        if (resp.has("Error")) {
            String err = resp.path("Error").path("Code").asText("") + ":" + resp.path("Error").path("Message").asText("");
            log.warn("腾讯云图片审核返回错误 {}: {}", requestId, err);
            return AuditResult.error("图片审核服务异常：" + err);
        }

        String suggestion = resp.path("Suggestion").asText("");
        String label = resp.path("Label").asText("");
        int score = resp.path("Score").asInt(0);
        String status;
        String message;
        switch (suggestion) {
            case "Pass" -> {
                status = "PASS";
                message = "机审通过";
            }
            case "Block" -> {
                status = "BLOCK";
                message = "图片审核未通过：" + describe(label);
            }
            case "Review" -> {
                // 疑似内容：按配置决定直接打回，还是留给管理员人工复核
                status = c.isReviewAsReject() ? "BLOCK" : "REVIEW";
                message = c.isReviewAsReject()
                        ? "图片审核未通过：" + describe(label)
                        : "图片需人工复核：" + describe(label);
            }
            default -> {
                status = "REVIEW";
                message = "机审结论未知，转人工复核";
            }
        }
        return new AuditResult(status, suggestion, label, score, requestId, message);
    }

    private String describe(String label) {
        if (label == null || label.isBlank()) return "疑似不适宜内容";
        return switch (label.toLowerCase(Locale.ROOT)) {
            case "porn" -> "疑似不适宜内容";
            case "violence", "terror" -> "疑似暴力或不安全内容";
            case "ad" -> "疑似广告导流内容";
            case "illegal" -> "疑似违规内容";
            case "abuse" -> "疑似不友善内容";
            default -> "疑似不适宜内容（" + label + "）";
        };
    }

    /** 腾讯云 TC3-HMAC-SHA256 签名（签名方法 v3） */
    private String sign(MomentProperties.Audit c, String payload, long ts) throws Exception {
        String date = UTC_DAY.format(Instant.ofEpochSecond(ts));
        String service = c.getEndpoint().split("\\.")[0];
        String contentType = "application/json; charset=utf-8";

        String canonicalRequest = "POST\n/\n\n"
                + "content-type:" + contentType + "\n"
                + "host:" + c.getEndpoint() + "\n\n"
                + "content-type;host\n"
                + sha256Hex(payload);

        String scope = date + "/" + service + "/tc3_request";
        String stringToSign = "TC3-HMAC-SHA256\n" + ts + "\n" + scope + "\n" + sha256Hex(canonicalRequest);

        byte[] secretDate = hmac(("TC3" + c.getSecretKey()).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmac(secretDate, service);
        byte[] secretSigning = hmac(secretService, "tc3_request");
        String signature = hex(hmac(secretSigning, stringToSign));

        return "TC3-HMAC-SHA256 Credential=" + c.getSecretId() + "/" + scope
                + ", SignedHeaders=content-type;host, Signature=" + signature;
    }

    private static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }
}
