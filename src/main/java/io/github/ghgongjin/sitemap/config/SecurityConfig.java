package io.github.ghgongjin.sitemap.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * @ClassName SecurityConfig
 * @Description Spring Security 配置：提供 BCrypt（DelegatingPasswordEncoder）bean 与过滤链。
 *              表单登录/退出与 CSRF 保护（/ws-progress/** 为 SockJS 协商流量豁免）已就位；
 *              报告/自动更新列表页游客可打开，报告详情/导出与自动更新详情/写操作需登录，其余路径仍放行
 * @Author gj
 * @Date 2026/9/19
 * @Version 1.0
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/register", "/css/**", "/js/**",
                                "/fonts/**", "/favicon.ico", "/error").permitAll()
                        // 列表页游客可直接打开（本人无数据时只见空态）；真正的查看/导出/写操作再登录（SavedRequest 回跳）
                        .requestMatchers(HttpMethod.GET, "/reports", "/auto").permitAll()
                        // SEO 报告详情/导出按用户隔离，游客先登录（SavedRequest 登录后回跳）
                        .requestMatchers("/reports", "/report/**").authenticated()
                        // 自动更新站点详情页与全部写操作按用户隔离，游客先登录
                        .requestMatchers("/auto", "/auto/**").authenticated()
                        .anyRequest().permitAll())   // 其余公开页（首页/预览/帮助等）保持放行
                .formLogin(form -> form
                        .loginPage("/login")
                        .failureUrl("/login?error=1")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))
                // /ws-progress/** 为 SockJS 协商流量（xhr_send 等 POST），无表单，豁免 CSRF；
                // 其余路径恢复默认 CSRF 保护
                .csrf(csrf -> csrf.ignoringRequestMatchers("/ws-progress/**"));
        return http.build();
    }
}
