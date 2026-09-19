package io.github.ghgongjin.sitemap.service;

/**
 * @ClassName RegistrationException
 * @Description 注册失败异常，携带失败原因枚举供上层做本地化提示
 * @Author gj
 * @Date 2026/9/19
 * @Version 1.0
 */
public class RegistrationException extends RuntimeException {

    public enum Reason { USERNAME_INVALID, USERNAME_TAKEN, PASSWORD_WEAK }

    private final Reason reason;

    public RegistrationException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
