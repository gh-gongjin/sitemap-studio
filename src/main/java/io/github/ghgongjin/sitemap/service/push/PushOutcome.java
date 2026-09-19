package io.github.ghgongjin.sitemap.service.push;

/**
 * @ClassName PushOutcome
 * @Description 一次推送的结果（skipped 表示未配置/停用/无版本，不产生日志）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
public record PushOutcome(
        boolean success,
        boolean skipped,
        PushErrorCode errorCode,
        String detail,
        Integer versionNumber,
        long durationMs) {

    public static PushOutcome skipped(String detail) {
        return new PushOutcome(false, true, null, detail, null, 0);
    }

    public static PushOutcome success(int versionNumber, String detail, long durationMs) {
        return new PushOutcome(true, false, null, detail, versionNumber, durationMs);
    }

    /** 无版本上下文的成功（如连接测试） */
    public static PushOutcome success(String detail, long durationMs) {
        return new PushOutcome(true, false, null, detail, null, durationMs);
    }

    public static PushOutcome failure(PushErrorCode errorCode, String detail,
                                      Integer versionNumber, long durationMs) {
        return new PushOutcome(false, false, errorCode, detail, versionNumber, durationMs);
    }
}
