package io.github.ghgongjin.sitemap.service.submission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

/**
 * Google Search Console sitemap 提交客户端：服务账号自签 JWT（RFC 7523）→ 换 access token
 * → PUT sitemaps.submit。token 不缓存（低频动作，省状态）。纯 JDK 密码学，零新增依赖。
 */
@Slf4j
@Component
public class GoogleSitemapClient {

    public static final String DEFAULT_BASE_URL = "https://searchconsole.googleapis.com";
    static final String SCOPE = "https://www.googleapis.com/auth/indexing";
    private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    /** JWT 有效期：Google 允许上限 1 小时 */
    static final long JWT_TTL_SECONDS = 3600;

    private final RestClient restClient;
    private final String baseUrl;
    private final ObjectMapper mapper;

    @Autowired
    public GoogleSitemapClient(RestClient.Builder builder,
                               @Value("${sitemap.submission.gsc-base-url:" + DEFAULT_BASE_URL + "}")
                               String baseUrl,
                               ObjectMapper mapper,
                               @Value("${sitemap.submission.timeout-ms:10000}") int timeoutMs) {
        this(builder.requestFactory(BaiduPushClient.timeoutRequestFactory(timeoutMs)).build(),
                baseUrl, mapper);
    }

    /** 测试缝（package-private）：直接注入已构建的 RestClient（MockRestServiceServer 绑定后 build） */
    GoogleSitemapClient(RestClient restClient, String baseUrl, ObjectMapper mapper) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
        this.mapper = mapper;
    }

    public void submitSitemap(GoogleServiceAccount account, String siteUrl, String sitemapUrl)
            throws SubmissionClientException {
        String accessToken = exchangeToken(account, buildJwt(account, Instant.now().getEpochSecond()));
        log.info("GSC sitemap 提交：property={}，sitemap={}", siteUrl, sitemapUrl);
        String uri = baseUrl + "/webmasters/v3/sites/"
                + BaiduPushClient.percentEncode(siteUrl) + "/sitemaps/"
                + BaiduPushClient.percentEncode(sitemapUrl);
        try {
            restClient.put()
                    .uri(URI.create(uri))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 403) {
                throw new SubmissionClientException(SubmissionErrorCode.GSC_NOT_A_SITE_USER,
                        "服务账号未被加入该 GSC 站点（HTTP 403），请在 GSC「用户和权限」中添加该账号", e);
            }
            throw new SubmissionClientException(SubmissionErrorCode.GSC_API_REJECTED,
                    "GSC 拒绝提交（HTTP " + status + "）", e);
        } catch (RestClientException e) {
            throw new SubmissionClientException(SubmissionErrorCode.GSC_NETWORK,
                    "无法连接 GSC：" + e.getMessage(), e);
        }
    }

    String buildJwt(GoogleServiceAccount account, long nowSeconds) throws SubmissionClientException {
        var header = mapper.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        var claims = mapper.createObjectNode();
        claims.put("iss", account.clientEmail());
        claims.put("scope", SCOPE);
        claims.put("aud", account.tokenUri());
        claims.put("iat", nowSeconds);
        claims.put("exp", nowSeconds + JWT_TTL_SECONDS);
        String signingInput;
        try {
            signingInput = base64Url(mapper.writeValueAsString(header))
                    + "." + base64Url(mapper.writeValueAsString(claims));
        } catch (Exception e) {
            throw new SubmissionClientException(SubmissionErrorCode.CONFIG_INVALID,
                    "JWT 声明序列化失败：" + e.getMessage(), e);
        }
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(account.privateKey());
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + base64Url(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new SubmissionClientException(SubmissionErrorCode.CONFIG_INVALID,
                    "JWT 签名失败：" + e.getMessage(), e);
        }
    }

    private String exchangeToken(GoogleServiceAccount account, String jwt)
            throws SubmissionClientException {
        String body;
        try {
            body = restClient.post()
                    .uri(URI.create(account.tokenUri()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=" + BaiduPushClient.percentEncode(GRANT_TYPE)
                            + "&assertion=" + jwt)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new SubmissionClientException(SubmissionErrorCode.GSC_TOKEN_REJECTED,
                    "授权端点拒绝请求（HTTP " + e.getStatusCode().value()
                            + "），请核对服务账号 JSON 是否有效", e);
        } catch (RestClientException e) {
            throw new SubmissionClientException(SubmissionErrorCode.GSC_NETWORK,
                    "无法连接授权端点：" + e.getMessage(), e);
        }
        try {
            JsonNode node = mapper.readTree(body == null || body.isBlank() ? "{}" : body);
            String token = node.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                throw new SubmissionClientException(SubmissionErrorCode.GSC_TOKEN_REJECTED,
                        "授权响应缺少 access_token");
            }
            return token;
        } catch (SubmissionClientException e) {
            throw e;
        } catch (Exception e) {
            throw new SubmissionClientException(SubmissionErrorCode.GSC_TOKEN_REJECTED,
                    "授权响应无法解析", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String base64Url(String text) {
        return base64Url(text.getBytes(StandardCharsets.UTF_8));
    }
}
