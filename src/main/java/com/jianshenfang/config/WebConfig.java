package com.jianshenfang.config;

import com.jianshenfang.auth.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final MomentProperties momentProperties;
    private final WxProperties wxProperties;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*");
    }

    /**
     * 本地开发直出上传图片；生产由 Nginx 的 location /upload/ 接管，不会走到这里。
     * 文件名为随机 UUID，且不在 /api/** 下，故不受登录拦截器保护（与设计稿一致）。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = Paths.get(momentProperties.getUploadDir()).toAbsolutePath().normalize();
        // toUri() 生成 file:///C:/... 或 file:///opt/... 形式，Windows/Linux 通用
        String location = dir.toUri().toString();
        if (!location.endsWith("/")) location += "/";
        String prefix = momentProperties.getUrlPrefix();
        if (!prefix.startsWith("/")) prefix = "/" + prefix;
        registry.addResourceHandler(prefix + "/**").addResourceLocations(location);

        // 微信头像转存目录：同样不在 /api/** 下，不受登录拦截器保护
        Path avatarDir = Paths.get(wxProperties.getAvatarUploadDir()).toAbsolutePath().normalize();
        String avatarLocation = avatarDir.toUri().toString();
        if (!avatarLocation.endsWith("/")) avatarLocation += "/";
        String avatarPrefix = wxProperties.getAvatarUrlPrefix();
        if (!avatarPrefix.startsWith("/")) avatarPrefix = "/" + avatarPrefix;
        registry.addResourceHandler(avatarPrefix + "/**").addResourceLocations(avatarLocation);
    }
}
