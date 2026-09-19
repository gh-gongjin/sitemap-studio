package io.github.ghgongjin.sitemap.config;

import io.github.ghgongjin.sitemap.security.SecurityUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * @ClassName GlobalModelAdvice
 * @Description 全站模型属性：向所有 Thymeleaf 页面注入 currentUsername，供顶栏登录态区渲染
 * @Author gj
 * @Date 2026/9/19
 * @Version 1.0
 */
@ControllerAdvice
public class GlobalModelAdvice {

    @ModelAttribute("currentUsername")
    public String currentUsername() {
        return SecurityUtils.currentUsername();
    }
}
