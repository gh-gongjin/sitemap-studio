package io.github.ghgongjin.sitemap.service;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * @ClassName SecurityUtilsTestConfig
 * @Description @DataJpaTest 不加载主上下文 @Configuration，这里显式提供 PasswordEncoder bean
 * @Author gj
 * @Date 2026/9/19
 * @Version 1.0
 */
@TestConfiguration
public class SecurityUtilsTestConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
