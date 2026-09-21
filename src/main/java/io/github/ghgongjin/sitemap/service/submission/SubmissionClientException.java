package io.github.ghgongjin.sitemap.service.submission;

/**
 * 提交通道客户端异常：携带分类码，编排层据此写日志并翻译成人话
 */
public class SubmissionClientException extends Exception {

    private final SubmissionErrorCode errorCode;

    public SubmissionClientException(SubmissionErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public SubmissionClientException(SubmissionErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public SubmissionErrorCode errorCode() {
        return errorCode;
    }
}
