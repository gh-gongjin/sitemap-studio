package io.github.ghgongjin.sitemap.service.submission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleSitemapClientTest {

    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String SUBMIT_URL = "https://searchconsole.googleapis.com/webmasters/v3/sites/"
            + "sc-domain%3Aexample.com/sitemaps/https%3A%2F%2Fexample.com%2Fsitemap.xml";

    private static KeyPair keyPair;
    private static GoogleServiceAccount account;

    @BeforeAll
    static void setUpAccount() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'})
                        .encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        String json = "{\"type\":\"service_account\","
                + "\"client_email\":\"sa@proj.iam.gserviceaccount.com\","
                + "\"token_uri\":\"" + TOKEN_URL + "\","
                + "\"private_key\":" + new ObjectMapper().writeValueAsString(pem) + "}";
        account = GoogleServiceAccount.parse(json);
    }

    private MockRestServiceServer server;
    private GoogleSitemapClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        // 走 package-private 测试缝：mock server 只能绑定在 builder 上，
        // 而生产构造器会覆盖 builder 的 requestFactory，故此处直接注入绑定后 build 的客户端
        client = new GoogleSitemapClient(builder.build(), GoogleSitemapClient.DEFAULT_BASE_URL,
                new ObjectMapper());
    }

    @Test
    void shouldConfigureTenSecondTimeoutRequestFactoryWhenConstructedFromBuilder() {
        // Given: spec §5.3 承诺连接/读取各 10s——构造时必须把带超时的请求工厂装进 builder
        // （装配断言与百度客户端共用 SubmissionClientTestSupport，一处契约一处验法）
        SubmissionClientTestSupport.assertBuilderUsesTimeoutFactory(10_000,
                builder -> new GoogleSitemapClient(builder, GoogleSitemapClient.DEFAULT_BASE_URL,
                        new ObjectMapper(), 10_000));
    }

    @Test
    void shouldBuildVerifiableJwtWithClaimsWhenAsked() throws Exception {
        String jwt = client.buildJwt(account, 1_700_000_000L);
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);

        String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(header).contains("\"alg\":\"RS256\"");
        assertThat(claims).contains("\"iss\":\"sa@proj.iam.gserviceaccount.com\"")
                .contains(GoogleSitemapClient.SCOPE)
                .contains("\"aud\":\"" + TOKEN_URL + "\"")
                .contains("\"iat\":1700000000")
                .contains("\"exp\":1700003600");

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @Test
    void shouldSubmitSitemapWhenTokenAndApiAccept() throws Exception {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                // brief 原文 request.getBody().readAllBytes() 无法编译：ClientHttpRequest.getBody()
                // 静态类型为 OutputStream（无公开 readAllBytes），此处按 Spring 内部同款做法
                // 转为 ByteArrayOutputStream 读取，断言语义不变
                .andExpect(request -> {
                    String body = ((java.io.ByteArrayOutputStream) request.getBody())
                            .toString(StandardCharsets.UTF_8);
                    assertThat(body)
                            .startsWith("grant_type="
                                    + URLEncoderEncode("urn:ietf:params:oauth:grant-type:jwt-bearer"))
                            .contains("&assertion=");
                })
                .andRespond(withSuccess("{\"access_token\":\"ya29.tok\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(SUBMIT_URL))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("Authorization", "Bearer ya29.tok"))
                .andRespond(withSuccess());

        client.submitSitemap(account, "sc-domain:example.com", "https://example.com/sitemap.xml");

        server.verify();
    }

    private static String URLEncoderEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Test
    void shouldThrowTokenRejectedWhenTokenEndpointReturns400() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .body("{\"error\":\"invalid_grant\"}").contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.submitSitemap(account,
                "sc-domain:example.com", "https://example.com/sitemap.xml"))
                .isInstanceOf(SubmissionClientException.class)
                .satisfies(e -> assertThat(((SubmissionClientException) e).errorCode())
                        .isEqualTo(SubmissionErrorCode.GSC_TOKEN_REJECTED));
    }

    @Test
    void shouldThrowUnauthorizedWhenApiReturns401() throws Exception {
        // Given: 401 = access token 无效/过期，属凭据与授权类，不得混入 GSC_API_REJECTED
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"ya29.tok\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(SUBMIT_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        // When & Then
        assertThatThrownBy(() -> client.submitSitemap(account,
                "sc-domain:example.com", "https://example.com/sitemap.xml"))
                .isInstanceOf(SubmissionClientException.class)
                .satisfies(e -> assertThat(((SubmissionClientException) e).errorCode())
                        .isEqualTo(SubmissionErrorCode.GSC_UNAUTHORIZED))
                .hasMessageContaining("凭据");
    }

    @Test
    void shouldThrowNotASiteUserWhenApiReturns403() throws Exception {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"ya29.tok\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(SUBMIT_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.submitSitemap(account,
                "sc-domain:example.com", "https://example.com/sitemap.xml"))
                .isInstanceOf(SubmissionClientException.class)
                .satisfies(e -> assertThat(((SubmissionClientException) e).errorCode())
                        .isEqualTo(SubmissionErrorCode.GSC_NOT_A_SITE_USER))
                .hasMessageContaining("用户和权限");
    }

    @Test
    void shouldThrowApiRejectedWhenApiReturns500() throws Exception {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"ya29.tok\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(SUBMIT_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.submitSitemap(account,
                "sc-domain:example.com", "https://example.com/sitemap.xml"))
                .isInstanceOf(SubmissionClientException.class)
                .satisfies(e -> assertThat(((SubmissionClientException) e).errorCode())
                        .isEqualTo(SubmissionErrorCode.GSC_API_REJECTED))
                .hasMessageContaining("500");
    }

    @Test
    void shouldThrowTokenRejectedWhenAccessTokenMissingFromResponse() throws Exception {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"expires_in\":3600}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.submitSitemap(account,
                "sc-domain:example.com", "https://example.com/sitemap.xml"))
                .isInstanceOf(SubmissionClientException.class)
                .satisfies(e -> assertThat(((SubmissionClientException) e).errorCode())
                        .isEqualTo(SubmissionErrorCode.GSC_TOKEN_REJECTED))
                .hasMessageContaining("access_token");
    }
}
