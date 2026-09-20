package io.github.ghgongjin.sitemap.service.notify;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

/**
 * @ClassName MailSender
 * @Description 邮件通知通道：仅当部署者配置 spring.mail.host 时装配；
 *              text+html multipart，模板固定双语、只渲染变量，HTML 转义交 Thymeleaf
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.mail.host")
public class MailSender implements NotifyChannel {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final String from;

    public MailSender(JavaMailSender mailSender, TemplateEngine templateEngine,
                      @Value("${spring.mail.host:}") String host,
                      @Value("${spring.mail.username:}") String username) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.from = username == null || username.isBlank() ? "sitemap-studio@" + host : username;
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public boolean send(AutoSite site, NotificationPayload payload) {
        if (site.getNotifyEmail() == null || site.getNotifyEmail().isBlank()) {
            return false;
        }
        try {
            Context context = new Context(java.util.Locale.ENGLISH, Map.of("p", payload));
            String html = templateEngine.process(templateFor(payload.type()), context);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(site.getNotifyEmail());
            helper.setSubject("[Sitemap Studio] " + subject(payload.type()));
            helper.setText(plainText(payload), html);
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.warn("邮件通知发送失败：siteId={}，{}", site.getId(), e.getMessage());
            return false;
        }
    }

    private static String templateFor(NotificationType type) {
        return switch (type) {
            case CHANGED -> "mail/notify-changed";
            case FAILED -> "mail/notify-failed";
            case TEST -> "mail/notify-test";
        };
    }

    private static String subject(NotificationType type) {
        return switch (type) {
            case CHANGED -> "站点有更新 / Site updated";
            case FAILED -> "站点更新失败 / Site update failed";
            case TEST -> "测试通知 / Test notification";
        };
    }

    private static String plainText(NotificationPayload p) {
        StringBuilder sb = new StringBuilder();
        sb.append("站点 Site: ").append(p.siteUrl()).append('\n');
        if (p.versionNumber() != null) {
            sb.append("版本 Version: v").append(p.versionNumber()).append('\n');
            sb.append("变化 Changes: +").append(p.added())
                    .append(" / -").append(p.removed())
                    .append(" / ~").append(p.changed()).append('\n');
        }
        if (p.consecutiveFailures() != null) {
            sb.append("连续失败 Consecutive failures: ").append(p.consecutiveFailures())
                    .append("  原因 reason: ").append(p.failureMessage()).append('\n');
        }
        if (p.seoErrorCount() != null) {
            sb.append("SEO 错误 Errors: ").append(p.seoErrorCount())
                    .append("  阈值 threshold: ").append(p.seoErrorThreshold()).append('\n');
        }
        sb.append("详情见站点详情页 / See the site detail page in Sitemap Studio.\n");
        return sb.toString();
    }
}
