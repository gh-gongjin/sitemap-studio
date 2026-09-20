package io.github.ghgongjin.sitemap.service.notify;

import io.github.ghgongjin.sitemap.entity.AutoSite;

/**
 * @ClassName NotifyChannel
 * @Description 通知通道契约：send 返回 false 表示失败/未送达，绝不向主流程抛业务异常
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
public interface NotifyChannel {

    String name();          // "webhook" | "email"

    boolean send(AutoSite site, NotificationPayload payload);
}
