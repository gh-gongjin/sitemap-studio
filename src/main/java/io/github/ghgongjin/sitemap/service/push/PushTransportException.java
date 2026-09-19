package io.github.ghgongjin.sitemap.service.push;

/**
 * @ClassName PushTransportException
 * @Description 推送传输异常（携带错误分类码，message 为面向用户的简短说明）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
public class PushTransportException extends Exception {

    private final PushErrorCode errorCode;

    public PushTransportException(PushErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PushTransportException(PushErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public PushErrorCode errorCode() {
        return errorCode;
    }
}
