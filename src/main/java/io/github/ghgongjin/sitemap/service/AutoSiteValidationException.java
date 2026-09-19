package io.github.ghgongjin.sitemap.service;

import java.util.Arrays;

/**
 * @ClassName AutoSiteValidationException
 * @Description 自动更新站点校验失败异常：只携带 i18n message key 与占位符参数，不再硬编码中文提示。
 *              控制器把它放进 flashError / flashErrorArgs，模板经 MessageSource（#messages）按当前
 *              语言解析；继承 IllegalArgumentException 以沿用控制器既有的捕获口径
 * @Author gj
 * @Date 2026/9/19
 * @Version 1.0
 */
public class AutoSiteValidationException extends IllegalArgumentException {

    private final String messageKey;
    private final Object[] args;

    public AutoSiteValidationException(String messageKey, Object... args) {
        super(messageKey);
        this.messageKey = messageKey;
        this.args = args == null ? new Object[0] : args;
    }

    /**
     * messages*.properties 中的键，中英双份
     */
    public String messageKey() {
        return messageKey;
    }

    /**
     * MessageFormat 占位符参数，可能为空数组
     */
    public Object[] args() {
        return args;
    }

    @Override
    public String toString() {
        return messageKey + Arrays.toString(args);
    }
}
