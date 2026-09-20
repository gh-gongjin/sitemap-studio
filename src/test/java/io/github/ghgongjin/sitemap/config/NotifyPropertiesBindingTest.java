package io.github.ghgongjin.sitemap.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName NotifyPropertiesBindingTest
 * @Description 通知配置绑定：application.properties 提供全部键，默认值与 spec 一致
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
@SpringBootTest
@ActiveProfiles("test")
class NotifyPropertiesBindingTest {

    @Autowired
    private NotifyProperties props;

    @Test
    void shouldBindDefaultsFromApplicationProperties() {
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getWebhookTimeoutMs()).isEqualTo(10000);
        assertThat(props.isAllowPrivateNetwork()).isTrue();
        assertThat(props.getMaxEmailsPerSitePerDay()).isEqualTo(50);
    }
}
