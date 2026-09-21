package io.github.ghgongjin.sitemap.service.submission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BaiduPushClientTest {

    private static final String URI_WITH_QUERY =
            "https://data.zz.baidu.com/urls?site=https%3A%2F%2Fexample.com&token=t0ken_ABC-123";

    private MockRestServiceServer server;
    private BaiduPushClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new BaiduPushClient(builder, BaiduPushClient.DEFAULT_ENDPOINT, new ObjectMapper());
    }

    @Test
    void shouldPostUrlsAsPlainTextWhenEndpointAccepts() throws Exception {
        server.expect(requestTo(URI_WITH_QUERY))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("https://example.com/a\nhttps://example.com/b"))
                .andRespond(withSuccess("{\"success\":2,\"remain\":498}", MediaType.APPLICATION_JSON));

        BaiduPushClient.BaiduPushResponse response =
                client.push("https://example.com", "t0ken_ABC-123",
                        List.of("https://example.com/a", "https://example.com/b"));

        assertThat(response.success()).isEqualTo(2);
        assertThat(response.remain()).isEqualTo(498);
        server.verify();
    }

    @Test
    void shouldReportRemainMinusOneWhenBodyLacksRemain() throws Exception {
        server.expect(requestTo(URI_WITH_QUERY))
                .andRespond(withSuccess("{\"success\":1}", MediaType.APPLICATION_JSON));

        assertThat(client.push("https://example.com", "t0ken_ABC-123",
                List.of("https://example.com/a")).remain()).isEqualTo(-1);
    }

    @Test
    void shouldThrowQuotaExhaustedWhenErrorCodeFour() {
        server.expect(requestTo(URI_WITH_QUERY)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .body("{\"error\":4,\"message\":\"over quota\"}").contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.push("https://example.com", "t0ken_ABC-123",
                List.of("https://example.com/a")))
                .isInstanceOf(SubmissionClientException.class)
                .satisfies(e -> assertThat(((SubmissionClientException) e).errorCode())
                        .isEqualTo(SubmissionErrorCode.BAIDU_REJECTED))
                .hasMessageContaining("配额");
    }

    @Test
    void shouldThrowConfigInvalidWhenErrorCodeOneOrTwo() {
        server.expect(requestTo(URI_WITH_QUERY)).andRespond(
                withSuccess("{\"error\":1,\"message\":\"token invalid\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.push("https://example.com", "t0ken_ABC-123",
                List.of("https://example.com/a")))
                .isInstanceOf(SubmissionClientException.class)
                .satisfies(e -> assertThat(((SubmissionClientException) e).errorCode())
                        .isEqualTo(SubmissionErrorCode.CONFIG_INVALID));
    }

    @Test
    void shouldThrowRejectedWhenOtherError() {
        server.expect(requestTo(URI_WITH_QUERY)).andRespond(
                withSuccess("{\"error\":9,\"message\":\"site forbidden\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.push("https://example.com", "t0ken_ABC-123",
                List.of("https://example.com/a")))
                .isInstanceOf(SubmissionClientException.class)
                .satisfies(e -> assertThat(((SubmissionClientException) e).errorCode())
                        .isEqualTo(SubmissionErrorCode.BAIDU_REJECTED))
                .hasMessageContaining("9");
    }

    @Test
    void shouldThrowTransportWhenServerErrorWithoutJsonError() {
        server.expect(requestTo(URI_WITH_QUERY)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("<html>500</html>").contentType(MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.push("https://example.com", "t0ken_ABC-123",
                List.of("https://example.com/a")))
                .isInstanceOf(SubmissionClientException.class)
                .satisfies(e -> assertThat(((SubmissionClientException) e).errorCode())
                        .isEqualTo(SubmissionErrorCode.BAIDU_TRANSPORT))
                .hasMessageContaining("500");
    }

    @Test
    void shouldThrowRejectedWhenBodyNotRecognizable() {
        server.expect(requestTo(URI_WITH_QUERY)).andRespond(withSuccess("not-json", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.push("https://example.com", "t0ken_ABC-123",
                List.of("https://example.com/a")))
                .isInstanceOf(SubmissionClientException.class)
                .satisfies(e -> assertThat(((SubmissionClientException) e).errorCode())
                        .isEqualTo(SubmissionErrorCode.BAIDU_REJECTED));
    }
}
