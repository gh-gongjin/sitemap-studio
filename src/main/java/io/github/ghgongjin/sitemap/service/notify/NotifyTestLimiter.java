package io.github.ghgongjin.sitemap.service.notify;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @ClassName NotifyTestLimiter
 * @Description 测试通知限流：每站点滑动窗口 3 次/60s；键空间=站点数、窗口外自动清空，天然有界；
 *              队列表并发取用，窗口内操作对队列对象加锁，保证同站判定原子
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
@Component
public class NotifyTestLimiter {

    private static final int MAX_PER_WINDOW = 3;
    private static final long WINDOW_MS = 60_000L;

    private final Map<Long, Deque<Long>> hits = new ConcurrentHashMap<>();

    public boolean allow(Long siteId) {
        if (siteId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Deque<Long> queue = hits.computeIfAbsent(siteId, k -> new ArrayDeque<>());
        // ArrayDeque 非线程安全：computeIfAbsent 只保证队列身份，窗口判定仍需按队列互斥
        synchronized (queue) {
            queue.removeIf(t -> now - t >= WINDOW_MS);
            if (queue.size() >= MAX_PER_WINDOW) {
                return false;
            }
            queue.addLast(now);
            return true;
        }
    }
}
