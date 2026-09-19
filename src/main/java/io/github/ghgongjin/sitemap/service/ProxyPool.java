package io.github.ghgongjin.sitemap.service;

import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 代理池管理器
 * 管理爬虫代理列表，支持轮询选择和健康状态追踪
 */
@Slf4j
public class ProxyPool {

    private final List<ProxyInfo> proxies;
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);
    private final ConcurrentHashMap<String, ProxyStatus> proxyStatusMap = new ConcurrentHashMap<>();

    public ProxyPool(List<String> proxyList) {
        this.proxies = new ArrayList<>();
        if (proxyList != null) {
            for (String proxyStr : proxyList) {
                ProxyInfo info = parseProxy(proxyStr);
                if (info != null) {
                    proxies.add(info);
                    proxyStatusMap.put(info.getKey(), new ProxyStatus());
                }
            }
        }
        log.info("代理池初始化完成，共 {} 个代理", proxies.size());
    }

    /**
     * 解析代理字符串，格式：host:port 或 host:port:username:password
     */
    private ProxyInfo parseProxy(String proxyStr) {
        if (proxyStr == null || proxyStr.trim().isEmpty()) {
            return null;
        }
        try {
            String[] parts = proxyStr.trim().split(":");
            if (parts.length < 2) {
                log.warn("代理格式错误：{}", proxyStr);
                return null;
            }
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);
            String username = parts.length > 2 ? parts[2] : null;
            String password = parts.length > 3 ? parts[3] : null;
            return new ProxyInfo(host, port, username, password);
        } catch (Exception e) {
            log.warn("解析代理失败：{} - {}", proxyStr, e.getMessage());
            return null;
        }
    }

    /**
     * 获取下一个可用代理（轮询方式）
     *
     * @return 可用代理，如果没有可用代理则返回 null（表示直连）
     */
    public Proxy getNextProxy() {
        if (proxies.isEmpty()) {
            return null;
        }

        int size = proxies.size();
        int startIndex = roundRobinIndex.getAndIncrement() % size;

        // 从 startIndex 开始查找可用代理
        for (int i = 0; i < size; i++) {
            int index = (startIndex + i) % size;
            ProxyInfo info = proxies.get(index);
            ProxyStatus status = proxyStatusMap.get(info.getKey());

            if (status != null && status.isAvailable()) {
                log.debug("使用代理：{}", info);
                return info.toProxy();
            }
        }

        log.debug("无可用代理，使用直连");
        return null;
    }

    /**
     * 获取代理信息（包含认证信息）
     */
    public ProxyInfo getNextProxyInfo() {
        if (proxies.isEmpty()) {
            return null;
        }

        int size = proxies.size();
        int startIndex = roundRobinIndex.getAndIncrement() % size;

        for (int i = 0; i < size; i++) {
            int index = (startIndex + i) % size;
            ProxyInfo info = proxies.get(index);
            ProxyStatus status = proxyStatusMap.get(info.getKey());

            if (status != null && status.isAvailable()) {
                return info;
            }
        }

        return null;
    }

    /**
     * 标记代理成功
     */
    public void markSuccess(ProxyInfo proxy) {
        if (proxy == null) return;
        ProxyStatus status = proxyStatusMap.get(proxy.getKey());
        if (status != null) {
            status.markSuccess();
        }
    }

    /**
     * 标记代理失败
     */
    public void markFailure(ProxyInfo proxy) {
        if (proxy == null) return;
        ProxyStatus status = proxyStatusMap.get(proxy.getKey());
        if (status != null) {
            status.markFailure();
            if (!status.isAvailable()) {
                log.warn("代理不可用：{}", proxy);
            }
        }
    }

    /**
     * 重置所有代理状态
     */
    public void resetAll() {
        for (ProxyStatus status : proxyStatusMap.values()) {
            status.reset();
        }
        log.info("已重置所有代理状态");
    }

    /**
     * 获取可用代理数量
     */
    public int getAvailableCount() {
        int count = 0;
        for (ProxyStatus status : proxyStatusMap.values()) {
            if (status.isAvailable()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 代理信息
     */
    public static class ProxyInfo {
        private final String host;
        private final int port;
        private final String username;
        private final String password;

        public ProxyInfo(String host, int port, String username, String password) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
        }

        public String getHost() { return host; }
        public int getPort() { return port; }
        public String getUsername() { return username; }
        public String getPassword() { return password; }

        public String getKey() {
            return host + ":" + port;
        }

        public Proxy toProxy() {
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
        }

        public boolean hasAuth() {
            return username != null && !username.isEmpty();
        }

        public String getAuthHeader() {
            if (!hasAuth()) return null;
            String credentials = username + ":" + (password != null ? password : "");
            return "Basic " + java.util.Base64.getEncoder().encodeToString(credentials.getBytes());
        }

        @Override
        public String toString() {
            return host + ":" + port + (hasAuth() ? " (auth)" : "");
        }
    }

    /**
     * 代理状态追踪
     */
    private static class ProxyStatus {
        private static final int MAX_FAILURES = 3;
        private static final long COOLDOWN_MS = 60000; // 1 分钟冷却

        private int consecutiveFailures = 0;
        private long lastFailureTime = 0;
        private boolean available = true;

        public synchronized boolean isAvailable() {
            if (available) {
                return true;
            }
            // 检查冷却期是否结束
            if (System.currentTimeMillis() - lastFailureTime > COOLDOWN_MS) {
                available = true;
                consecutiveFailures = 0;
                return true;
            }
            return false;
        }

        public synchronized void markSuccess() {
            consecutiveFailures = 0;
            available = true;
        }

        public synchronized void markFailure() {
            consecutiveFailures++;
            lastFailureTime = System.currentTimeMillis();
            if (consecutiveFailures >= MAX_FAILURES) {
                available = false;
            }
        }

        public synchronized void reset() {
            consecutiveFailures = 0;
            available = true;
            lastFailureTime = 0;
        }
    }
}
