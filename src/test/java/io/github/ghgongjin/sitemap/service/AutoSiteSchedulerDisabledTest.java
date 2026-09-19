package io.github.ghgongjin.sitemap.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName AutoSiteSchedulerDisabledTest
 * @Description 自动更新开关关闭时不注册调度器（测试与走查环境不触发自动爬取）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@SpringBootTest(properties = "sitemap.auto-update.enabled=false")
@ActiveProfiles("test")
class AutoSiteSchedulerDisabledTest {

    @Autowired(required = false)
    private AutoSiteScheduler scheduler;

    @Test
    void shouldSkipSchedulerBeanWhenDisabled() {
        // Then
        assertThat(scheduler).isNull();
    }
}
