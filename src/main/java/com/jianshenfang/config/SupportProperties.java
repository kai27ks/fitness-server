package com.jianshenfang.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 系统开发维护联系方式；修改配置后重启生效。 */
@Data
@Component
@ConfigurationProperties(prefix = "app.support")
public class SupportProperties {
    private boolean enabled = true;
    private String title = "系统维护与技术支持";
    private String description = "使用问题反馈、功能建议或系统开发维护咨询，欢迎联系。";
    private String developerName = "";
    private String phone = "";
    private String email = "";
}
