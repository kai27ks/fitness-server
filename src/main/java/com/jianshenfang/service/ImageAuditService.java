package com.jianshenfang.service;

/**
 * 图片内容安全审核。当前实现为腾讯云天御 IMS（ImageModeration），密钥/业务策略全部走配置预留。
 * 每张图片独立调用一次：前端一张一张上传，就一张一张审，任一张有问题只打回那一张。
 */
public interface ImageAuditService {

    AuditResult audit(byte[] jpegBytes, String dataId);

    /** 是否已开启机器审核；false 时业务侧按「人工审核」开关决定入队或立即展示 */
    boolean enabled();

    record AuditResult(String status, String suggestion, String label, Integer score,
                       String requestId, String message) {

        public static AuditResult skipped() {
            return new AuditResult("SKIPPED", null, null, null, null, "未进行机器审核");
        }

        public static AuditResult error(String message) {
            return new AuditResult("ERROR", null, null, null, null, message);
        }
    }
}
