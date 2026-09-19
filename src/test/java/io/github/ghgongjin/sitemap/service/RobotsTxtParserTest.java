package io.github.ghgongjin.sitemap.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RobotsTxtParserTest {

    @Test
    void shouldRethrowSecurityExceptionInsteadOfFailingOpen() throws Exception {
        SafeHttpFetcher fetcher = mock(SafeHttpFetcher.class);
        when(fetcher.fetch(eq("https://93.184.216.34/robots.txt"), eq("https://93.184.216.34"), anyInt(), isNull()))
                .thenThrow(new SecurityException("禁止跨域访问"));

        RobotsTxtParser parser = new RobotsTxtParser();
        assertThatThrownBy(() -> parser.parse("https://93.184.216.34", new CrawlUrlPolicy(), fetcher, 1000))
                .isInstanceOf(SecurityException.class);
    }
}
