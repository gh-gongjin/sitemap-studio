package io.github.ghgongjin.sitemap.config;

import io.github.ghgongjin.sitemap.service.notify.WebhookUrlPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;

/**
 * @ClassName NotificationConfig
 * @Description 告警底座：webhook 策略 Bean + notifyExecutor 专用小池（2 线程/队列 100，满丢最旧），
 *              与爬取/推送线程完全隔离；丢最旧的兜底分支只丢新任务，保证不递归
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
@Slf4j
@Configuration
@EnableAsync
@RequiredArgsConstructor
public class NotificationConfig {

    private final NotifyProperties props;

    @Bean
    public WebhookUrlPolicy webhookUrlPolicy() {
        return new WebhookUrlPolicy(props.isAllowPrivateNetwork());
    }

    @Bean(name = "notifyExecutor")
    public ThreadPoolTaskExecutor notifyExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("notify-");
        executor.setRejectedExecutionHandler((runnable, pool) -> {
            if (pool.getQueue().poll() != null) {
                log.warn("通知队列已满，丢弃最早的一条通知");
                try {
                    pool.execute(runnable);
                } catch (RejectedExecutionException e) {
                    log.warn("通知任务被丢弃（二次拒绝）");
                }
            } else {
                log.warn("通知队列已满且无可丢旧任务，丢弃新通知");
            }
        });
        executor.initialize();
        return executor;
    }
}
