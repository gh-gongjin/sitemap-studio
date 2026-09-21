package io.github.ghgongjin.sitemap.service.submission;

import org.mockito.ArgumentCaptor;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @ClassName SubmissionClientTestSupport
 * @Description 提交客户端共享测试工具：mock-Builder 超时装配断言（BaiduPushClient / GoogleSitemapClient
 *              生产构造器同款契约——必须把带出网超时的请求工厂装进 RestClient.Builder）
 * @Author gj
 * @Date 2026/9/21
 * @Version 1.0
 */
final class SubmissionClientTestSupport {

    private SubmissionClientTestSupport() {
    }

    /**
     * 以 mock 的 RestClient.Builder 调用被测客户端构造器，断言注入的请求工厂
     * 为 SimpleClientHttpRequestFactory 且连接/读取超时均为 timeoutMs。
     */
    static void assertBuilderUsesTimeoutFactory(int timeoutMs, Consumer<RestClient.Builder> constructor) {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.requestFactory(any())).thenReturn(builder);
        when(builder.build()).thenReturn(RestClient.builder().build());

        constructor.accept(builder);

        ArgumentCaptor<ClientHttpRequestFactory> captor =
                ArgumentCaptor.forClass(ClientHttpRequestFactory.class);
        verify(builder).requestFactory(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SimpleClientHttpRequestFactory.class);
        SimpleClientHttpRequestFactory factory = (SimpleClientHttpRequestFactory) captor.getValue();
        assertThat(ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(timeoutMs);
        assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(timeoutMs);
    }
}
