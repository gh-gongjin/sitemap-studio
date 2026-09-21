package io.github.ghgongjin.sitemap.service.submission;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @ClassName SubmissionHttpTest
 * @Description 包内共享 HTTP 管道：超时请求工厂归位 SubmissionHttp，两客户端不再跨类互借
 * @Author gj
 * @Date 2026/9/21
 * @Version 1.0
 */
class SubmissionHttpTest {

    @Test
    void shouldSetConnectAndReadTimeoutWhenBuildingFactory() {
        SimpleClientHttpRequestFactory factory = SubmissionHttp.timeoutRequestFactory(10_000);

        assertThat(ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(10_000);
        assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(10_000);
    }

    @Test
    void shouldHonorCustomTimeoutValueWhenBuildingFactory() {
        SimpleClientHttpRequestFactory factory = SubmissionHttp.timeoutRequestFactory(5_000);

        assertThat(ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(5_000);
        assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(5_000);
    }

    @Test
    void shouldNotExposeTimeoutFactoryOnBaiduClientAnymore() {
        // 跨类静态依赖红线：GoogleSitemapClient 曾借 BaiduPushClient.timeoutRequestFactory——已迁包内共享
        assertThatThrownBy(() -> BaiduPushClient.class.getDeclaredMethod(
                "timeoutRequestFactory", int.class))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void shouldBePackagePrivateUtilityClass() {
        assertThat(Modifier.isPublic(SubmissionHttp.class.getModifiers())).isFalse();
        assertThat(SubmissionHttp.class.getDeclaredConstructors())
                .allSatisfy(c -> assertThat(Modifier.isPrivate(c.getModifiers())).isTrue());
    }

    @Test
    void shouldKeepTimeoutFactoryStaticWhenExposed() throws Exception {
        Method method = SubmissionHttp.class.getDeclaredMethod("timeoutRequestFactory", int.class);
        assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
    }

    @Test
    void shouldEncodeSpaceAsPercent20WhenPercentEncoding() {
        assertThat(SubmissionHttp.percentEncode("https://example.org/a b"))
                .isEqualTo("https%3A%2F%2Fexample.org%2Fa%20b");
    }

    @Test
    void shouldNotExposePercentEncodeOnBaiduClientAnymore() {
        // 跨类静态依赖红线：GoogleSitemapClient 曾借 BaiduPushClient.percentEncode——已迁包内共享
        assertThatThrownBy(() -> BaiduPushClient.class.getDeclaredMethod("percentEncode", String.class))
                .isInstanceOf(NoSuchMethodException.class);
    }
}
