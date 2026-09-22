package io.github.ghgongjin.sitemap.service.submission;

/**
 * 一次多通道提交的结果（skipped：未配置/无版本/无同域 URL，不产生日志）
 */
public record SubmissionOutcome(boolean success, boolean skipped, String detail) {

    public static SubmissionOutcome skipped(String detail) {
        return new SubmissionOutcome(false, true, detail);
    }

    public static SubmissionOutcome success(String detail) {
        return new SubmissionOutcome(true, false, detail);
    }

    public static SubmissionOutcome failure(String detail) {
        return new SubmissionOutcome(false, false, detail);
    }
}
