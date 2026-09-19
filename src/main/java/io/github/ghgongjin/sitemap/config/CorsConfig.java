package io.github.ghgongjin.sitemap.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @ClassName CorsConfig
 * @Description CORS 跨域配置类
 * @Author gj
 * @Date 2026/3/9
 * @Version 1.0
 */
@Slf4j
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
  public void addCorsMappings(CorsRegistry registry) {
        // 添加 CORS 支持，允许所有来源访问（开发环境）
   registry.addMapping("/**")
               .allowedOriginPatterns("*")  // 使用 allowedOriginPatterns 更灵活
               .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH", "HEAD")
               .allowedHeaders("*")
               .exposedHeaders(
                   "Access-Control-Allow-Origin",
                   "Access-Control-Allow-Credentials",
                   "Access-Control-Allow-Headers", 
                   "Authorization", 
                   "Content-Type",
                   "X-Requested-With",
                   "Accept",
                   "Origin")
               .allowCredentials(true)  // 允许携带凭证
               .maxAge(3600);  // 预检请求缓存时间
       
      log.info("CORS 跨域配置已启用（允许所有来源 - 开发环境）");
    }
}
