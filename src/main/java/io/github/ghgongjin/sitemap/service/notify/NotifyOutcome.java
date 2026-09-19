package io.github.ghgongjin.sitemap.service.notify;

/**
 * @ClassName NotifyOutcome
 * @Description 单通道投递结论：success + 可本地化的 messageKey + 调试细节（仅进日志）
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
public record NotifyOutcome(boolean success, String messageKey, String detail) {}
