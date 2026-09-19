package io.github.ghgongjin.sitemap.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName AutoSiteSchedulerWiringTest
 * @Description 自动更新调度装配验证：开关生效 + @Scheduled 表达式可解析
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@SpringBootTest(properties = {
        "sitemap.auto-update.enabled=true",
        "sitemap.auto-update.initial-delay-ms=600000",
        "sitemap.auto-update.scan-interval-ms=600000"})
@ActiveProfiles("test")
class AutoSiteSchedulerWiringTest {

    @Autowired
    private AutoSiteScheduler scheduler;

    @Autowired
    private AutoSiteUpdater updater;

    @Test
    void shouldRegisterSchedulerWhenEnabled() {
        // Then
        assertThat(scheduler).isNotNull();
        assertThat(updater).isNotNull();
    }

    @Test
    void shouldDeclareConfigurableScheduleOnScan() throws Exception {
        // When
        Scheduled scheduled = AutoSiteScheduler.class.getMethod("scan").getAnnotation(Scheduled.class);

        // Then
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.initialDelayString()).isEqualTo("${sitemap.auto-update.initial-delay-ms:30000}");
        assertThat(scheduled.fixedDelayString()).isEqualTo("${sitemap.auto-update.scan-interval-ms:60000}");
    }
}
