package io.github.ghgongjin.sitemap.service.notify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName NotifyTestLimiterTest
 * @Description 测试通知限流器：每站滑动窗口 3 次/60s、站点间互不影响、空站点直接拒绝
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
class NotifyTestLimiterTest {

    @Test
    void shouldAllowThreeThenDenyFourthWithinWindow() {
        NotifyTestLimiter limiter = new NotifyTestLimiter();

        assertThat(limiter.allow(1L)).isTrue();
        assertThat(limiter.allow(1L)).isTrue();
        assertThat(limiter.allow(1L)).isTrue();
        assertThat(limiter.allow(1L)).isFalse();
        assertThat(limiter.allow(1L)).isFalse();
    }

    @Test
    void shouldTrackBudgetPerSite() {
        NotifyTestLimiter limiter = new NotifyTestLimiter();

        limiter.allow(1L);
        limiter.allow(1L);
        limiter.allow(1L);

        assertThat(limiter.allow(2L)).isTrue();
        assertThat(limiter.allow(1L)).isFalse();
    }

    @Test
    void shouldDenyNullSiteId() {
        NotifyTestLimiter limiter = new NotifyTestLimiter();

        assertThat(limiter.allow(null)).isFalse();
    }

    @Test
    void shouldStayConsistentUnderConcurrentAttempts() {
        NotifyTestLimiter limiter = new NotifyTestLimiter();
        int threads = 16;
        java.util.concurrent.atomic.AtomicInteger allowed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        Runnable task = () -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (limiter.allow(42L)) {
                allowed.incrementAndGet();
            }
        };
        for (int i = 0; i < threads; i++) {
            new Thread(task).start();
        }
        start.countDown();
        for (int i = 0; i < threads * 50; i++) {
            if (limiter.allow(42L)) {
                // 配额只有 3 个：任何情况下都不允许第 4 次放行
                allowed.incrementAndGet();
            }
        }

        assertThat(allowed.get()).isEqualTo(3);
    }
}
