package io.github.ghgongjin.sitemap.service.notify;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @ClassName MailSenderTest
 * @Description 邮件通道：无收件人跳过、multipart 渲染含计数与站点地址、异常吞为 false
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
class MailSenderTest {

    // 说明：生产注入的 SpringTemplateEngine（SpEL）即此类；裸 org.thymeleaf.TemplateEngine
    // 默认走 OGNL，而 Spring Boot 的 thymeleaf-spring6 不带 ognl 依赖，离线仓库亦无 jar，
    // 故用 SpringTemplateEngine + ClassLoaderTemplateResolver 渲染真实模板文件（模板语法错误会使测试失败）。
    private static TemplateEngine templateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private static MailSender sender(JavaMailSenderImpl mailSender) {
        return new MailSender(mailSender, templateEngine(), "smtp.example.com", "bot@example.com");
    }

    private static AutoSite siteWithEmail() {
        AutoSite site = new AutoSite();
        site.setId(1L);
        site.setUrl("https://example.com");
        site.setNotifyEmail("owner@example.com");
        return site;
    }

    private static MimeMessage freshMessage(JavaMailSenderImpl mailSender) {
        JavaMailSenderImpl offline = new JavaMailSenderImpl();
        offline.setHost("localhost");
        return offline.createMimeMessage();
    }

    @Test
    void shouldSkipWhenNoRecipientConfigured() {
        JavaMailSenderImpl mailSender = mock(JavaMailSenderImpl.class);
        AutoSite site = new AutoSite();
        site.setId(1L);
        site.setUrl("https://example.com");

        assertThat(sender(mailSender).send(site,
                new NotificationPayload(NotificationType.TEST, "https://example.com", null,
                        0, 0, 0, null, -1, null, null, LocalDateTime.now()))).isFalse();
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void shouldSendMultipartWithCountsAndSiteUrl() throws Exception {
        JavaMailSenderImpl mailSender = mock(JavaMailSenderImpl.class);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> freshMessage(mailSender));
        NotificationPayload payload = new NotificationPayload(NotificationType.CHANGED,
                "https://example.com", 2, 3, 1, 2, 5, 3, null, null, LocalDateTime.now());

        assertThat(sender(mailSender).send(siteWithEmail(), payload)).isTrue();

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        assertThat(sent.getHeader("Subject")).isNotNull();
        String content = readAll(sent);
        assertThat(content).contains("https://example.com").contains("+3").contains("-1").contains("~2");
        // HTML part 由 Thymeleaf 渲染
        assertThat(content).contains("multipart/alternative");
    }

    @Test
    void shouldReturnFalseWhenSendThrows() throws Exception {
        JavaMailSenderImpl mailSender = mock(JavaMailSenderImpl.class);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> freshMessage(mailSender));
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("smtp down"))
                .when(mailSender).send(any(MimeMessage.class));
        NotificationPayload payload = new NotificationPayload(NotificationType.FAILED,
                "https://example.com", null, 0, 0, 0, null, -1, 1, "connect timeout", LocalDateTime.now());

        assertThat(sender(mailSender).send(siteWithEmail(), payload)).isFalse();
    }

    private static String readAll(MimeMessage message) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        message.writeTo(out);
        return new String(out.toByteArray(), StandardCharsets.ISO_8859_1); // multipart 边界/文本 part 为 quoted-printable，URL 与数字直出
    }
}
