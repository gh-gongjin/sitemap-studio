package io.github.ghgongjin.sitemap.service.submission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * @ClassName GoogleServiceAccount
 * @Description GSC 服务账号 JSON 的解析与入口校验：坏凭据在保存时拒绝，不留到每次提交静默失败。
 * token_uri 主机白名单固定为 oauth2.googleapis.com（凭据外送红线）。
 * @Author gj
 * @Date 2026/9/21
 * @Version 1.0
 */
public record GoogleServiceAccount(String clientEmail, String tokenUri, PrivateKey privateKey) {

    static final String REQUIRED_TOKEN_HOST = "oauth2.googleapis.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static GoogleServiceAccount parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("服务账号 JSON 无法解析：" + e.getMessage());
        }
        if (!"service_account".equals(root.path("type").asText())) {
            throw new IllegalArgumentException("服务账号 JSON 的 type 必须是 service_account");
        }
        String clientEmail = requireText(root, "client_email");
        String tokenUri = requireText(root, "token_uri");
        String privateKeyPem = requireText(root, "private_key");
        requireGoogleTokenEndpoint(tokenUri);
        return new GoogleServiceAccount(clientEmail, tokenUri, parsePkcs8(privateKeyPem));
    }

    private static String requireText(JsonNode root, String field) {
        String value = root.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("服务账号 JSON 缺少字段：" + field);
        }
        return value.trim();
    }

    private static void requireGoogleTokenEndpoint(String tokenUri) {
        URI uri;
        try {
            uri = URI.create(tokenUri);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("token_uri 不是合法 URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !REQUIRED_TOKEN_HOST.equalsIgnoreCase(uri.getHost())) {
            throw new IllegalArgumentException("token_uri 必须是 https://" + REQUIRED_TOKEN_HOST + " 的地址");
        }
    }

    private static PrivateKey parsePkcs8(String pem) {
        String body = pem
                .replaceAll("-----BEGIN [A-Z ]*-----", "")
                .replaceAll("-----END [A-Z ]*-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getMimeDecoder().decode(body);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("私钥无法解析（需要 PKCS#8 PEM）：" + e.getMessage());
        }
    }

    @Override
    public String toString() {
        return "GoogleServiceAccount[clientEmail=" + clientEmail + "]";
    }
}
