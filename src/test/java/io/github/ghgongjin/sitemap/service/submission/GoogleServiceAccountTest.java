package io.github.ghgongjin.sitemap.service.submission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @ClassName GoogleServiceAccountTest
 * @Description GSC 服务账号 JSON 解析与入口校验的单元测试（含 token_uri 白名单与 PKCS#8 私钥解析）
 * @Author gj
 * @Date 2026/9/21
 * @Version 1.0
 */
class GoogleServiceAccountTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static KeyPair keyPair;
    private static String pem;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'})
                        .encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    private static String json(String type, String email, String tokenUri, String privateKey) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type);
        node.put("client_email", email);
        node.put("token_uri", tokenUri);
        node.put("private_key", privateKey);
        return node.toString();
    }

    private static String valid() {
        return json("service_account", "sa@proj.iam.gserviceaccount.com",
                "https://oauth2.googleapis.com/token", pem);
    }

    @Test
    void shouldParseValidServiceAccountWhenAllFieldsPresent() {
        GoogleServiceAccount account = GoogleServiceAccount.parse(valid());
        assertThat(account.clientEmail()).isEqualTo("sa@proj.iam.gserviceaccount.com");
        assertThat(account.tokenUri()).isEqualTo("https://oauth2.googleapis.com/token");
        assertThat(account.privateKey()).isNotNull();
    }

    @Test
    void shouldRejectWhenTypeIsNotServiceAccount() {
        assertThatThrownBy(() -> GoogleServiceAccount.parse(
                json("authorized_user", "a@b.c", "https://oauth2.googleapis.com/token", pem)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service_account");
    }

    @Test
    void shouldRejectWhenRequiredFieldMissing() throws Exception {
        ObjectNode node = (ObjectNode) MAPPER.readTree(valid());
        node.remove("client_email");
        assertThatThrownBy(() -> GoogleServiceAccount.parse(node.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("client_email");
    }

    @Test
    void shouldRejectWhenTokenUriHostIsForeign() {
        assertThatThrownBy(() -> GoogleServiceAccount.parse(json("service_account",
                "a@b.c", "https://evil.example.com/token", pem)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oauth2.googleapis.com");
    }

    @Test
    void shouldRejectWhenTokenUriSchemeIsHttp() {
        assertThatThrownBy(() -> GoogleServiceAccount.parse(json("service_account",
                "a@b.c", "http://oauth2.googleapis.com/token", pem)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oauth2.googleapis.com");
    }

    @Test
    void shouldRejectWhenTokenUriHostIsLookAlike() {
        assertThatThrownBy(() -> GoogleServiceAccount.parse(json("service_account",
                "a@b.c", "https://oauth2.googleapis.com.evil.com/token", pem)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oauth2.googleapis.com");
    }

    @Test
    void shouldRejectWhenTokenUriHasNoScheme() {
        assertThatThrownBy(() -> GoogleServiceAccount.parse(json("service_account",
                "a@b.c", "oauth2.googleapis.com/token", pem)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oauth2.googleapis.com");
    }

    @Test
    void shouldRejectWhenPrivateKeyNotParseable() {
        assertThatThrownBy(() -> GoogleServiceAccount.parse(json("service_account",
                "a@b.c", "https://oauth2.googleapis.com/token", "-----BEGIN PRIVATE KEY-----\n###\n-----END PRIVATE KEY-----")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("私钥");
    }

    @Test
    void shouldRejectWhenContentIsNotJson() {
        assertThatThrownBy(() -> GoogleServiceAccount.parse("<html>"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldNotLeakPrivateKeyContentWhenJsonMalformed() {
        // 回归守卫：Jackson 解析失败异常若拼入源文（e.getMessage()），私钥正文会随错误文案外泄
        String keyBody = pem.substring(29, 89); // 私钥 PEM 主体连续片段（无换行）
        String malformed = "{\"type\":\"service_account\",\"client_email\":\"a@b.c\","
                + "\"token_uri\":\"https://oauth2.googleapis.com/token\","
                + "\"private_key\":\"" + keyBody + "\n  \"broken-json!!";

        assertThatThrownBy(() -> GoogleServiceAccount.parse(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(keyBody);
    }

    @Test
    void shouldNotLeakPrivateKeyContentWhenKeyParsingFails() {
        // 回归守卫：PKCS#8 解析失败文案只描述错误，不得回显私钥片段
        String keyBody = pem.substring(29, 89);
        String brokenPem = "-----BEGIN PRIVATE KEY-----\n" + keyBody + "@@invalid@@\n"
                + "-----END PRIVATE KEY-----";

        assertThatThrownBy(() -> GoogleServiceAccount.parse(
                json("service_account", "a@b.c", "https://oauth2.googleapis.com/token", brokenPem)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(keyBody);
    }
}
