package io.github.ghgongjin.sitemap.service.submission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 百度主动推送客户端（普通收录 API）：POST text/plain 一行一 URL；
 * 响应 success/remain 与 error/message 两态，错误码分类进 SubmissionClientException。
 */
@Slf4j
@Component
public class BaiduPushClient {

    public static final String DEFAULT_ENDPOINT = "https://data.zz.baidu.com/urls";
    /** 百度响应 code=4：当天配额为 0 或已用尽 */
    static final int ERROR_QUOTA_EXHAUSTED = 4;
    /** 百度响应 code=1/2：token 或 site 参数非法 */
    static final int ERROR_TOKEN_EMPTY = 1;
    static final int ERROR_SITE_INVALID = 2;

    private final RestClient restClient;
    private final String endpoint;
    private final ObjectMapper mapper;

    public BaiduPushClient(RestClient.Builder builder,
                           @Value("${sitemap.submission.baidu-endpoint:" + DEFAULT_ENDPOINT + "}")
                           String endpoint,
                           ObjectMapper mapper) {
        this.restClient = builder.build();
        this.endpoint = endpoint;
        this.mapper = mapper;
    }

    public record BaiduPushResponse(int success, int remain) {
    }

    public BaiduPushResponse push(String site, String token, List<String> urls)
            throws SubmissionClientException {
        String uri = endpoint + "?site=" + percentEncode(site) + "&token=" + percentEncode(token);
        log.info("百度主动推送：site={}，{} 个 URL", site, urls.size());
        String body;
        try {
            body = restClient.post()
                    .uri(URI.create(uri))
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(String.join("\n", urls))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw translateHttpFailure(e);
        } catch (RestClientException e) {
            throw new SubmissionClientException(SubmissionErrorCode.BAIDU_TRANSPORT,
                    "百度推送请求失败：" + e.getMessage(), e);
        }
        return parseResponse(body);
    }

    private SubmissionClientException translateHttpFailure(RestClientResponseException e) {
        SubmissionClientException mapped = tryErrorNode(e.getResponseBodyAsString());
        if (mapped != null) {
            return mapped;
        }
        return new SubmissionClientException(SubmissionErrorCode.BAIDU_TRANSPORT,
                "百度推送失败（HTTP " + e.getStatusCode().value() + "）", e);
    }

    private BaiduPushResponse parseResponse(String body) throws SubmissionClientException {
        if (body == null || body.isBlank()) {
            throw rejected("响应为空");
        }
        JsonNode node = readTreeOrRejected(body);
        SubmissionClientException error = errorFromNode(node);
        if (error != null) {
            throw error;
        }
        if (!node.has("success")) {
            throw rejected("无法识别的响应：" + abbreviate(body));
        }
        return new BaiduPushResponse(node.path("success").asInt(0), node.path("remain").asInt(-1));
    }

    /** 百度业务错误对象 {"error":code,"message":…} → 分类异常；非错误对象返回 null */
    private SubmissionClientException errorFromNode(JsonNode node) {
        if (!node.has("error")) {
            return null;
        }
        int code = node.path("error").asInt(-1);
        String message = node.path("message").asText("");
        return switch (code) {
            case ERROR_QUOTA_EXHAUSTED -> new SubmissionClientException(
                    SubmissionErrorCode.BAIDU_REJECTED,
                    "当天配额已用尽（error=4），剩余 URL 将在后续提交中送达");
            case ERROR_TOKEN_EMPTY, ERROR_SITE_INVALID -> new SubmissionClientException(
                    SubmissionErrorCode.CONFIG_INVALID,
                    "token 或站点参数无效（error=" + code + "），请核对提交设置");
            default -> new SubmissionClientException(SubmissionErrorCode.BAIDU_REJECTED,
                    "百度拒绝提交：error=" + code
                            + (message.isBlank() ? "" : "，" + abbreviate(message)));
        };
    }

    private SubmissionClientException tryErrorNode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return errorFromNode(mapper.readTree(body));
        } catch (Exception ignored) {
            return null;
        }
    }

    private JsonNode readTreeOrRejected(String body) throws SubmissionClientException {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw rejected("响应不是合法 JSON：" + abbreviate(body));
        }
    }

    private SubmissionClientException rejected(String detail) {
        return new SubmissionClientException(SubmissionErrorCode.BAIDU_REJECTED,
                "百度响应异常：" + detail);
    }

    private String abbreviate(String value) {
        String trimmed = value.trim();
        return trimmed.length() <= 160 ? trimmed : trimmed.substring(0, 160) + "…";
    }

    static String percentEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
