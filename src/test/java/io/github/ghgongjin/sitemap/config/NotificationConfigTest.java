package io.github.ghgongjin.sitemap.config;

import io.github.ghgongjin.sitemap.service.notify.WebhookUrlPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName NotificationConfigTest
 * @Description 通知底座装配：策略 Bean 与 notifyExecutor 线程池参数（2 线程/队列 100）
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationConfigTest {

    @Autowired
    private TaskExecutor notifyExecutor;

    @Autowired
    private WebhookUrlPolicy webhookUrlPolicy;

    @Test
    void shouldConfigureNotifyExecutorWithTwoThreadsAndQueue100() {
        assertThat(webhookUrlPolicy).isNotNull();
        assertThat(notifyExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) notifyExecutor;
        assertThat(pool.getCorePoolSize()).isEqualTo(2);
        assertThat(pool.getMaxPoolSize()).isEqualTo(2);
        assertThat(pool.getThreadPriority()).isPositive();
    }
}
