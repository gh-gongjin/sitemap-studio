package io.github.ghgongjin.sitemap.service.push;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * @ClassName IndexNowClientTest
 * @Description IndexNow 客户端单元测试：key 生成、loc 提取（防外部实体）、HTTP 提交与错误分类
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
class IndexNowClientTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path tempDir;

    private MockRestServiceServer server;
    private IndexNowClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new IndexNowClient(builder);
    }

    @Test
    void shouldReturnFreshThirtyTwoCharHexKeyWhenGenerating() {
        // When
        String first = IndexNowClient.generateKey();
        String second = IndexNowClient.generateKey();

        // Then
        assertThat(first).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(second).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void shouldExtractAllLocUrlsWhenSitemapParsed() {
        // Given
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc>https://example.com/a</loc></url>
                  <url><loc>https://example.com/b</loc></url>
                </urlset>
                """;

        // When
        List<String> urls = IndexNowClient.extractUrls(xml);

        // Then
        assertThat(urls).containsExactly("https://example.com/a", "https://example.com/b");
    }

    @Test
    void shouldNotResolveExternalEntityWhenXmlHasDoctype() throws Exception {
        // Given
        Path secret = tempDir.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET-CONTENT");
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE urlset [<!ENTITY xxe SYSTEM "%s">]>
                <urlset><url><loc>&xxe;</loc></url><url><loc>https://example.com/ok</loc></url></urlset>
                """.formatted(secret.toUri());

        // When
        List<String> urls = IndexNowClient.extractUrls(xml);

        // Then
        assertThat(urls).contains("https://example.com/ok");
        assertThat(urls).allSatisfy(url -> assertThat(url).doesNotContain("TOP-SECRET-CONTENT"));
    }

    @Test
    void shouldSubmitPayloadWhenEndpointAccepts() throws Exception {
        // Given
        server.expect(requestTo(IndexNowClient.ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.host").value("example.com"))
                .andExpect(jsonPath("$.key").value(KEY))
                .andExpect(jsonPath("$.keyLocation").value("https://example.com/" + KEY + ".txt"))
                .andExpect(jsonPath("$.urlList[0]").value("https://example.com/a"))
                .andExpect(jsonPath("$.urlList[1]").value("https://example.com/b"))
                .andRespond(withSuccess());

        // When
        client.submit("https://example.com", KEY, List.of("https://example.com/a", "https://example.com/b"));

        // Then
        server.verify();
    }

    @Test
    void shouldThrowIndexNowFailedWhenEndpointRejectsKey() {
        // Given
        server.expect(requestTo(IndexNowClient.ENDPOINT)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        // When & Then
        assertThatThrownBy(() -> client.submit("https://example.com", KEY, List.of("https://example.com/a")))
                .isInstanceOf(PushTransportException.class)
                .satisfies(e -> assertThat(((PushTransportException) e).errorCode())
                        .isEqualTo(PushErrorCode.INDEXNOW_FAILED))
                .hasMessageContaining("403");
    }

    @Test
    void shouldThrowIndexNowFailedWhenEndpointUnreachable() {
        // Given
        server.expect(requestTo(IndexNowClient.ENDPOINT))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        // When & Then
        assertThatThrownBy(() -> client.submit("https://example.com", KEY, List.of("https://example.com/a")))
                .isInstanceOf(PushTransportException.class)
                .satisfies(e -> assertThat(((PushTransportException) e).errorCode())
                        .isEqualTo(PushErrorCode.INDEXNOW_FAILED));
    }
}
