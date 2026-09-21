# 搜索引擎提交（功能 C）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在既有 IndexNow 之上新增百度主动推送与 Google Search Console sitemap 提交两个通道，自动更新推送成功后顺带提交，失败止步于提交日志。

**Architecture:** 扩展 `push_config`（8 列，凭据 CredentialCipher 加密）+ 新包 `service/submission`（`BaiduPushClient` / `GoogleSitemapClient` 两个纯 HTTP 客户端 + `SearchEngineSubmissionService` 编排），GSC 授权用 JDK 自签 JWT（RFC 7523），零新增 Maven 依赖。

**Tech Stack:** Spring Boot 3.5.16 / Java 21 / Spring Data JPA (H2) / RestClient + MockRestServiceServer / Jackson（Boot 自带）/ Thymeleaf / JUnit 5 + Mockito + AssertJ

**Spec:** `docs/superpowers/specs/2026-09-21-search-engine-submission-design.md`（已批准，PR #18）

## Global Constraints

- 包根 `io.github.ghgongjin.sitemap`；Spring Boot 3.5.16 / Java 21；**零新增 Maven 依赖**
- H2 + ddl-auto=update；给存量表加 NOT NULL 列必须带 `@ColumnDefault`（v1.1.1 skipped_pages 500 教训）+ schema 守卫测试
- 离线构建 `mvn -o`；全量测试基线 **539 通过 / 0 失败 / 7 跳过**，只增不减；新测试不得依赖宿主机 locale
- 出网端点白名单硬编码：`data.zz.baidu.com`、`oauth2.googleapis.com`（token_uri host 校验）、`searchconsole.googleapis.com`；端点覆写只允许 `sitemap.submission.*` 配置属性（启动级），不接受用户表单输入
- `CrawlUrlPolicy` 一行不动；全部新端点走 `requireOwned` 用户隔离（他人站点 404）
- 凭据（百度 token / GSC 服务账号 JSON）一律 CredentialCipher 加密落库、页面永不回显明文；GSC `client_email` 存明文列（非秘密）
- 前端一律自研系统风格控件（复用 push-panel 样式），禁原生 confirm/校验气泡/下拉；文案 zh/en 双语（`MessagesAlignmentTest` 强制对齐）
- 提交失败绝不改站点状态、绝不影响推送结果（与 IndexNow 同待遇）；每通道只试一次不自动重试
- application.properties 只追加 ASCII 注释（本计划无需改 properties：端点默认值写在代码 @Value）
- git 提交用仓库级 noreply 身份（仓库 local config 已设，直接 `git commit` 即可）；提交信息中文

**通用测试命令**（Windows Git Bash，输出乱码时接 `| iconv -f GBK -t UTF-8`）：
单测 `mvn -o test -Dtest=类名`；全量 `mvn -o clean verify`。

**文件总览**

新建（main）：
- `entity/SubmissionLog.java`、`repository/SubmissionLogRepository.java`
- `service/submission/GoogleServiceAccount.java`、`SubmissionErrorCode.java`、`SubmissionClientException.java`
- `service/submission/BaiduPushClient.java`、`GoogleSitemapClient.java`
- `service/submission/SearchEngineSubmissionService.java`、`SubmissionOutcome.java`
- `service/push/SubmissionSettings.java`、`SubmissionView.java`

修改（main）：
- `entity/PushConfig.java`（+8 列）、`service/push/PushConfigService.java`（+saveSubmission/视图/日志/级联删除）
- `service/AutoSiteUpdater.java`（提交挂点）、`controller/AutoSiteController.java`（2 端点 + detail 模型）
- `templates/auto-detail.html`（提交区块）、`messages.properties` / `messages_en.properties`

测试：`repository/SubmissionSchemaDefaultsTest`、`service/submission/GoogleServiceAccountTest`、`service/push/SubmissionSettingsTest`、`service/submission/BaiduPushClientTest`、`service/submission/GoogleSitemapClientTest`、`service/submission/SearchEngineSubmissionServiceTest`；修改 `PushConfigServiceTest`（构造器）、`AutoSiteUpdaterTest`（构造器+新用例）、`AutoSiteControllerTest`（构造器+新用例）。

---

### Task 1: 数据底座 — PushConfig 扩展列 + SubmissionLog + schema 守卫

**Files:**
- Modify: `src/main/java/io/github/ghgongjin/sitemap/entity/PushConfig.java`（在 `indexNowKey` 字段后追加）
- Create: `src/main/java/io/github/ghgongjin/sitemap/entity/SubmissionLog.java`
- Create: `src/main/java/io/github/ghgongjin/sitemap/repository/SubmissionLogRepository.java`
- Test Create: `src/test/java/io/github/ghgongjin/sitemap/repository/SubmissionSchemaDefaultsTest.java`

**Interfaces:**
- Produces: `PushConfig` 新 getter/setter（Lombok `@Data` 生成）：`isBaiduEnabled()/setBaiduEnabled(boolean)`、`getBaiduSite()/setBaiduSite(String)`、`getBaiduTokenEnc()/setBaiduTokenEnc(String)`、`isGscEnabled()/setGscEnabled(boolean)`、`getGscSiteUrl()/setGscSiteUrl(String)`、`getGscSitemapUrl()/setGscSitemapUrl(String)`、`getGscServiceAccountJsonEnc()/setGscServiceAccountJsonEnc(String)`、`getGscClientEmail()/setGscClientEmail(String)`；`SubmissionLog`（常量 `STATUS_SUCCESS/STATUS_FAILED/CHANNEL_BAIDU/CHANNEL_GSC`）；`SubmissionLogRepository.findBySiteIdOrderByIdDesc(Long)`、`deleteBySiteId(Long)`。
- Consumes: 无（底座）。

- [ ] **Step 1: 先写失败的 schema 守卫测试**

```java
package io.github.ghgongjin.sitemap.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 存量表加 NOT NULL 列必须带库级默认值（skipped_pages 500 教训）：
 * 用新库 INFORMATION_SCHEMA 列定义等价断言旧库升级 ALTER（照 SeoReportSchemaDefaultsTest 模式）。
 */
@DataJpaTest
@ActiveProfiles("test")
class SubmissionSchemaDefaultsTest {

    @Autowired
    private JdbcTemplate jdbc;

    private String columnDefault(String table, String column) {
        return jdbc.queryForObject(
                "SELECT COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE UPPER(TABLE_NAME) = ? AND UPPER(COLUMN_NAME) = ?",
                String.class, table, column);
    }

    @Test
    void shouldCarryDefaultFalseOnBaiduEnabledWhenAddingColumnToLegacyTable() {
        assertThat(columnDefault("PUSH_CONFIG", "BAIDU_ENABLED")).matches("(?i)FALSE|0");
    }

    @Test
    void shouldCarryDefaultFalseOnGscEnabledWhenAddingColumnToLegacyTable() {
        assertThat(columnDefault("PUSH_CONFIG", "GSC_ENABLED")).matches("(?i)FALSE|0");
    }

    @Test
    void shouldCreateSubmissionLogTableWhenJpaBootstrap() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = 'SUBMISSION_LOG'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -o test -Dtest=SubmissionSchemaDefaultsTest`
Expected: FAIL —— 前两个用例 `EmptyResultDataAccessException`（列不存在）。

- [ ] **Step 3: PushConfig 加 8 列**

在 `indexNowKey` 字段声明之后插入（新增 import `org.hibernate.annotations.ColumnDefault;`）：

```java
    @Column(name = "baidu_enabled", nullable = false)
    @ColumnDefault("false")
    private boolean baiduEnabled;

    @Column(name = "baidu_site", length = 512)
    private String baiduSite;

    /** AES-256-GCM 加密后的百度主动推送 token（v1: 前缀） */
    @ToString.Exclude
    @Column(name = "baidu_token_enc", length = 4096)
    private String baiduTokenEnc;

    @Column(name = "gsc_enabled", nullable = false)
    @ColumnDefault("false")
    private boolean gscEnabled;

    /** GSC 已验证资源：sc-domain:example.com 或 https://example.com/ */
    @Column(name = "gsc_site_url", length = 512)
    private String gscSiteUrl;

    /** 公网可访问的 sitemap 完整地址（Google 抓的是它，不是本地文件） */
    @Column(name = "gsc_sitemap_url", length = 1024)
    private String gscSitemapUrl;

    /** AES-256-GCM 加密后的服务账号 JSON（含私钥，绝不出页面） */
    @ToString.Exclude
    @Column(name = "gsc_service_account_json_enc", length = 24576)
    private String gscServiceAccountJsonEnc;

    /** 服务账号邮箱（非秘密，GSC 用户列表可见），保存时从已验证 JSON 提取 */
    @Column(name = "gsc_client_email", length = 256)
    private String gscClientEmail;
```

- [ ] **Step 4: 新建 SubmissionLog 实体与仓储**

`entity/SubmissionLog.java`：

```java
package io.github.ghgongjin.sitemap.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 搜索引擎提交日志（每通道每次一行，每站保留最近 50 条）
 */
@Data
@Entity
@Table(name = "submission_log")
public class SubmissionLog {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String CHANNEL_BAIDU = "BAIDU";
    public static final String CHANNEL_GSC = "GSC";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Column(name = "version_number")
    private Integer versionNumber;

    @Column(name = "channel", nullable = false, length = 8)
    private String channel;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "error_code", length = 32)
    private String errorCode;

    @Column(name = "detail", length = 512)
    private String detail;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
```

`repository/SubmissionLogRepository.java`（import 风格照 `PushLogRepository`）：

```java
package io.github.ghgongjin.sitemap.repository;

import io.github.ghgongjin.sitemap.entity.SubmissionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 搜索引擎提交日志仓储（按站点查询，保留最近若干条）
 */
public interface SubmissionLogRepository extends JpaRepository<SubmissionLog, Long> {

    List<SubmissionLog> findBySiteIdOrderByIdDesc(Long siteId);

    void deleteBySiteId(Long siteId);
}
```

- [ ] **Step 5: 运行确认通过**

Run: `mvn -o test -Dtest=SubmissionSchemaDefaultsTest` → PASS（3 个）。
再跑 `mvn -o test -Dtest=PushConfigServiceTest`（既有）确认实体改动零破坏。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/ghgongjin/sitemap/entity/PushConfig.java \
        src/main/java/io/github/ghgongjin/sitemap/entity/SubmissionLog.java \
        src/main/java/io/github/ghgongjin/sitemap/repository/SubmissionLogRepository.java \
        src/test/java/io/github/ghgongjin/sitemap/repository/SubmissionSchemaDefaultsTest.java
git commit -m "feat: 搜索引擎提交数据底座（push_config 扩展 8 列 + submission_log 表）"
```

---

### Task 2: GoogleServiceAccount 解析（服务账号 JSON → 校验 + 私钥）

**Files:**
- Create: `src/main/java/io/github/ghgongjin/sitemap/service/submission/GoogleServiceAccount.java`
- Test Create: `src/test/java/io/github/ghgongjin/sitemap/service/submission/GoogleServiceAccountTest.java`

**Interfaces:**
- Produces: `record GoogleServiceAccount(String clientEmail, String tokenUri, java.security.PrivateKey privateKey)`；静态 `GoogleServiceAccount.parse(String json)`，非法输入抛 `IllegalArgumentException`（中文消息，与表单校验同通道）。`REQUIRED_TOKEN_HOST = "oauth2.googleapis.com"`（package-private 常量）。
- Consumes: 无。

- [ ] **Step 1: 先写失败测试**

RSA 密钥现场生成，用 Jackson 拼 JSON（`private_key` 换行由 Jackson 负责转义）：

```java
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
}
```

- [ ] **Step 2: 运行确认失败**（类不存在，编译错误即失败）

Run: `mvn -o test -Dtest=GoogleServiceAccountTest`

- [ ] **Step 3: 实现**

```java
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
 * GSC 服务账号 JSON 的解析与入口校验：坏凭据在保存时拒绝，不留到每次提交静默失败。
 * token_uri 主机白名单固定为 oauth2.googleapis.com（凭据外送红线）。
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
```

- [ ] **Step 4: 运行确认通过 + 回归**

Run: `mvn -o test -Dtest=GoogleServiceAccountTest` → PASS；`mvn -o test -Dtest=PushConfigServiceTest` → PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/ghgongjin/sitemap/service/submission/ \
        src/test/java/io/github/ghgongjin/sitemap/service/submission/GoogleServiceAccountTest.java
git commit -m "feat: GSC 服务账号 JSON 解析与入口校验（token_uri 白名单 + PKCS#8）"
```

---

### Task 3: 提交设置保存 — SubmissionSettings / saveSubmission / SubmissionView

**Files:**
- Create: `src/main/java/io/github/ghgongjin/sitemap/service/push/SubmissionSettings.java`
- Create: `src/main/java/io/github/ghgongjin/sitemap/service/push/SubmissionView.java`
- Modify: `src/main/java/io/github/ghgongjin/sitemap/service/push/PushConfigService.java`（构造器 +1 依赖、新增 3 个公开方法与校验私有方法、`delete` 级联）
- Test Create: `src/test/java/io/github/ghgongjin/sitemap/service/push/SubmissionSettingsTest.java`
- Test Modify: `src/test/java/io/github/ghgongjin/sitemap/service/push/PushConfigServiceTest.java`（仅 setUp 构造器一行）

**Interfaces:**
- Produces: `record SubmissionSettings(boolean baiduEnabled, String baiduSite, String baiduToken, boolean gscEnabled, String gscSiteUrl, String gscSitemapUrl, String serviceAccountJson)`；`record SubmissionView(boolean baiduEnabled, String baiduSite, boolean hasBaiduToken, boolean gscEnabled, String gscSiteUrl, String gscSitemapUrl, boolean hasGscJson, String gscClientEmail)` + `static SubmissionView of(PushConfig)`；`PushConfigService.saveSubmission(Long, SubmissionSettings): PushConfig`（抛 `IllegalArgumentException`）、`submissionView(Long): Optional<SubmissionView>`、`submissionLogs(Long): List<SubmissionLog>`；`PushConfigService` 新构造器签名 `(PushConfigRepository, PushLogRepository, SubmissionLogRepository, CredentialCipher)`。
- Consumes: Task 1 的 `PushConfig` 列与 `SubmissionLogRepository`；Task 2 的 `GoogleServiceAccount.parse`。

- [ ] **Step 1: 先写失败测试**

```java
package io.github.ghgongjin.sitemap.service.push;

import io.github.ghgongjin.sitemap.entity.PushConfig;
import io.github.ghgongjin.sitemap.repository.PushConfigRepository;
import io.github.ghgongjin.sitemap.repository.PushLogRepository;
import io.github.ghgongjin.sitemap.repository.SubmissionLogRepository;
import io.github.ghgongjin.sitemap.service.CredentialCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubmissionSettingsTest {

    private static final Long SITE_ID = 1L;
    private static final String GOOD_JSON = """
            {"type":"service_account","client_email":"sa@proj.iam.gserviceaccount.com",\
            "token_uri":"https://oauth2.googleapis.com/token",\
            "private_key":"-----BEGIN PRIVATE KEY-----\\nMIIBVwIBADAN\\n-----END PRIVATE KEY-----"}"";

    @TempDir
    Path tempDir;

    private PushConfigRepository configRepository;
    private CredentialCipher cipher;
    private PushConfigService service;

    @BeforeEach
    void setUp() {
        configRepository = mock(PushConfigRepository.class);
        cipher = new CredentialCipher("", tempDir.resolve("push.key").toString());
        service = new PushConfigService(configRepository, mock(PushLogRepository.class),
                mock(SubmissionLogRepository.class), cipher);
        when(configRepository.save(any(PushConfig.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 用真实 PKCS#8 私钥的合法 JSON（测试内生成一次，避免 mock parse） */
    private static String validGscJson() {
        try {
            var generator = java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + java.util.Base64.getMimeEncoder(64, new byte[] {'\n'})
                            .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----";
            return "{\"type\":\"service_account\",\"client_email\":\"sa@p.iam.gserviceaccount.com\","
                    + "\"token_uri\":\"https://oauth2.googleapis.com/token\",\"private_key\":"
                    + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(pem) + "}";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SubmissionSettings baiduOnly(String site, String token) {
        return new SubmissionSettings(true, site, token, false, "", "", "");
    }

    private static SubmissionSettings gscOnly(String property, String sitemapUrl, String json) {
        return new SubmissionSettings(false, "", "", true, property, sitemapUrl, json);
    }

    @Test
    void shouldCreateStandaloneConfigWhenNoPushConfigExists() {
        // Given 站点从未配过推送
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());

        // When
        PushConfig saved = service.saveSubmission(SITE_ID, baiduOnly("https://example.com", "abcd1234EFGH"));

        // Then 传输列有占位默认（not null 约束），提交列已写入
        assertThat(saved.getProtocol()).isEqualTo("SFTP");
        assertThat(saved.getHost()).isEqualTo("_submission_only_");
        assertThat(saved.isEnabled()).isFalse();
        assertThat(saved.isBaiduEnabled()).isTrue();
        assertThat(saved.getBaiduSite()).isEqualTo("https://example.com");
        assertThat(cipher.decrypt(saved.getBaiduTokenEnc())).isEqualTo("abcd1234EFGH");
    }

    @Test
    void shouldKeepStoredTokenWhenFormTokenBlankOnUpdate() {
        PushConfig existing = new PushConfig();
        existing.setSiteId(SITE_ID);
        existing.setBaiduTokenEnc(cipher.encrypt("old-token-123"));
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(existing));

        PushConfig saved = service.saveSubmission(SITE_ID, baiduOnly("https://example.com", "  "));

        assertThat(cipher.decrypt(saved.getBaiduTokenEnc())).isEqualTo("old-token-123");
    }

    @Test
    void shouldThrowWhenBaiduEnabledWithoutAnyStoredToken() {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveSubmission(SITE_ID, baiduOnly("https://example.com", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token");
    }

    @Test
    void shouldThrowWhenBaiduSiteHasPathOrPort() {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveSubmission(SITE_ID, baiduOnly("https://example.com/seo", "tok12345")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("站点");
        assertThatThrownBy(() -> service.saveSubmission(SITE_ID, baiduOnly("https://example.com:8443", "tok12345")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("站点");
    }

    @Test
    void shouldThrowWhenBaiduTokenHasIllegalCharacters() {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveSubmission(SITE_ID, baiduOnly("https://example.com", "tok en 1"))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldExtractClientEmailAndEncryptJsonWhenSaveValidGsc() {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());

        PushConfig saved = service.saveSubmission(SITE_ID,
                gscOnly("sc-domain:example.com", "https://example.com/sitemap.xml", validGscJson()));

        assertThat(saved.isGscEnabled()).isTrue();
        assertThat(saved.getGscSiteUrl()).isEqualTo("sc-domain:example.com");
        assertThat(saved.getGscClientEmail()).isEqualTo("sa@p.iam.gserviceaccount.com");
        assertThat(saved.getGscServiceAccountJsonEnc()).startsWith("v1:");
    }

    @Test
    void shouldThrowWhenGscJsonInvalidOnSave() {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveSubmission(SITE_ID,
                gscOnly("https://example.com/", "https://example.com/sitemap.xml", "{\"type\":\"x\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service_account");
    }

    @Test
    void shouldThrowWhenGscEnabledWithoutJsonStored() {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveSubmission(SITE_ID,
                gscOnly("https://example.com/", "https://example.com/sitemap.xml", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void shouldAcceptBothSiteUrlShapesForGsc() {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());
        PushConfig domain = service.saveSubmission(SITE_ID,
                gscOnly("sc-domain:example.com", "https://example.com/sitemap.xml", validGscJson()));
        PushConfig prefix = service.saveSubmission(SITE_ID,
                gscOnly("https://example.com/", "https://example.com/sitemap.xml", validGscJson()));
        assertThat(domain.getGscSiteUrl()).isEqualTo("sc-domain:example.com");
        assertThat(prefix.getGscSiteUrl()).isEqualTo("https://example.com/");
    }

    @Test
    void shouldThrowWhenSitemapUrlNotAbsolute() {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveSubmission(SITE_ID,
                gscOnly("sc-domain:example.com", "/sitemap.xml", validGscJson())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sitemap");
    }

    @Test
    void shouldExposeOnlyNonSecretFieldsInView() {
        PushConfig config = new PushConfig();
        config.setSiteId(SITE_ID);
        config.setBaiduEnabled(true);
        config.setBaiduSite("https://example.com");
        config.setBaiduTokenEnc(cipher.encrypt("secret-token-1"));
        config.setGscEnabled(true);
        config.setGscClientEmail("sa@p.iam.gserviceaccount.com");
        config.setGscServiceAccountJsonEnc(cipher.encrypt(GOOD_JSON));

        SubmissionView view = SubmissionView.of(config);

        assertThat(view.baiduEnabled()).isTrue();
        assertThat(view.baiduSite()).isEqualTo("https://example.com");
        assertThat(view.hasBaiduToken()).isTrue();
        assertThat(view.hasGscJson()).isTrue();
        assertThat(view.gscClientEmail()).isEqualTo("sa@p.iam.gserviceaccount.com");
        // 结构级防泄漏：视图记录没有任何凭据字段，也没有 JSON 字段
        assertThat(Arrays.stream(SubmissionView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList())
                .containsExactly("baiduEnabled", "baiduSite", "hasBaiduToken",
                        "gscEnabled", "gscSiteUrl", "gscSitemapUrl", "hasGscJson", "gscClientEmail");
        assertThat(view.toString()).doesNotContain("secret-token-1");
    }
}
```

- [ ] **Step 2: 运行确认失败**（record 与 saveSubmission 不存在，编译错误）

- [ ] **Step 3: 实现 records**

`SubmissionSettings.java`：

```java
package io.github.ghgongjin.sitemap.service.push;

/**
 * 搜索引擎提交设置表单输入（baiduToken/serviceAccountJson 为明文，仅保存过程短暂持有）
 */
public record SubmissionSettings(
        boolean baiduEnabled,
        String baiduSite,
        String baiduToken,
        boolean gscEnabled,
        String gscSiteUrl,
        String gscSitemapUrl,
        String serviceAccountJson) {

    @Override
    public String toString() {
        return "SubmissionSettings[baiduEnabled=" + baiduEnabled + ", baiduSite=" + baiduSite
                + ", gscEnabled=" + gscEnabled + ", gscSiteUrl=" + gscSiteUrl
                + ", gscSitemapUrl=" + gscSitemapUrl + ", credentials=***]";
    }
}
```

`SubmissionView.java`：

```java
package io.github.ghgongjin.sitemap.service.push;

import io.github.ghgongjin.sitemap.entity.PushConfig;

/**
 * 搜索引擎提交配置视图（供页面渲染；token 与 JSON 明文永不出现，client_email 是明文列）
 */
public record SubmissionView(
        boolean baiduEnabled,
        String baiduSite,
        boolean hasBaiduToken,
        boolean gscEnabled,
        String gscSiteUrl,
        String gscSitemapUrl,
        boolean hasGscJson,
        String gscClientEmail) {

    public static SubmissionView of(PushConfig config) {
        return new SubmissionView(
                config.isBaiduEnabled(),
                config.getBaiduSite(),
                hasText(config.getBaiduTokenEnc()),
                config.isGscEnabled(),
                config.getGscSiteUrl(),
                config.getGscSitemapUrl(),
                hasText(config.getGscServiceAccountJsonEnc()),
                config.getGscClientEmail());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
```

- [ ] **Step 4: PushConfigService 扩展**

构造器加第 3 个依赖 `SubmissionLogRepository submissionLogRepository`（字段在 `logRepository` 之后、`credentialCipher` 之前声明，新 import `SubmissionLog`、`SubmissionLogRepository`、`SubmissionView` 无需——同包；`java.net.URI` 需要）。新增：

```java
    static final String SUBMISSION_ONLY_HOST = "_submission_only_";
    /** 明文服务账号 JSON 上限：列宽 24576 覆盖 密文膨胀(iv+tag+base64) 后的余量 */
    private static final int SERVICE_ACCOUNT_JSON_MAX = 16384;
    private static final Pattern BAIDU_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    /**
     * 保存搜索引擎提交设置；凭据留空沿用已存值；坏凭据入口即拒
     */
    @Transactional
    public PushConfig saveSubmission(Long siteId, SubmissionSettings settings) {
        String baiduSite = requireBaiduSite(settings.baiduEnabled(), settings.baiduSite());
        String baiduToken = trimToNull(settings.baiduToken(), 64, "百度 token");
        if (baiduToken != null && !BAIDU_TOKEN_PATTERN.matcher(baiduToken).matches()) {
            throw new IllegalArgumentException("百度 token 只能是 8-64 位字母、数字、下划线或短横线");
        }
        String gscSiteUrl = requireGscSiteUrl(settings.gscEnabled(), settings.gscSiteUrl());
        String gscSitemapUrl = requireGscSitemapUrl(settings.gscEnabled(), settings.gscSitemapUrl());
        String json = trimToNull(settings.serviceAccountJson(), SERVICE_ACCOUNT_JSON_MAX, "服务账号 JSON");
        GoogleServiceAccount account = json == null ? null : GoogleServiceAccount.parse(json);

        LocalDateTime now = LocalDateTime.now();
        PushConfig config = configRepository.findBySiteId(siteId)
                .orElseGet(() -> submissionOnlyConfig(siteId, now));
        if (settings.baiduEnabled() && baiduToken == null && !hasText(config.getBaiduTokenEnc())) {
            throw new IllegalArgumentException("请输入百度推送 token");
        }
        if (settings.gscEnabled() && json == null && !hasText(config.getGscServiceAccountJsonEnc())) {
            throw new IllegalArgumentException("请粘贴服务账号 JSON");
        }

        config.setBaiduEnabled(settings.baiduEnabled());
        config.setBaiduSite(baiduSite);
        if (baiduToken != null) {
            config.setBaiduTokenEnc(credentialCipher.encrypt(baiduToken));
        }
        config.setGscEnabled(settings.gscEnabled());
        config.setGscSiteUrl(gscSiteUrl);
        config.setGscSitemapUrl(gscSitemapUrl);
        if (json != null) {
            config.setGscServiceAccountJsonEnc(credentialCipher.encrypt(json));
            config.setGscClientEmail(account.clientEmail());
        }
        config.setUpdatedAt(now);

        PushConfig saved = configRepository.save(config);
        log.info("搜索引擎提交设置已保存：siteId={}，百度={}，GSC={}",
                siteId, settings.baiduEnabled(), settings.gscEnabled());
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<SubmissionView> submissionView(Long siteId) {
        return siteId == null ? Optional.empty()
                : configRepository.findBySiteId(siteId).map(SubmissionView::of);
    }

    @Transactional(readOnly = true)
    public List<SubmissionLog> submissionLogs(Long siteId) {
        return siteId == null ? List.of() : submissionLogRepository.findBySiteIdOrderByIdDesc(siteId);
    }
```

`delete()` 里追加一行：`submissionLogRepository.deleteBySiteId(siteId);`

私有校验与占位工厂：

```java
    private String requireBaiduSite(boolean enabled, String value) {
        String site = trimToNull(value, 512, "百度站点");
        if (site == null) {
            if (enabled) {
                throw new IllegalArgumentException("百度站点不能为空（如 https://example.com）");
            }
            return null;
        }
        URI uri = parseHttpUri(site, "百度站点");
        if (uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isBlank() && !uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("百度站点必须是 https://example.com 形式（不含端口与路径）");
        }
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT);
    }

    private String requireGscSiteUrl(boolean enabled, String value) {
        String url = trimToNull(value, 512, "GSC 站点地址");
        if (url == null) {
            if (enabled) {
                throw new IllegalArgumentException("GSC 站点地址不能为空（sc-domain:example.com 或 https://example.com/）");
            }
            return null;
        }
        if (url.startsWith("sc-domain:")) {
            if (url.length() <= "sc-domain:".length() || url.substring("sc-domain:".length()).isBlank()) {
                throw new IllegalArgumentException("sc-domain: 后必须跟域名");
            }
            return url;
        }
        parseHttpUri(url, "GSC 站点地址");
        return url;
    }

    private String requireGscSitemapUrl(boolean enabled, String value) {
        String url = trimToNull(value, 1024, "sitemap 公开 URL");
        if (url == null) {
            if (enabled) {
                throw new IllegalArgumentException("sitemap 公开 URL 不能为空");
            }
            return null;
        }
        parseHttpUri(url, "sitemap 公开 URL");
        return url;
    }

    private URI parseHttpUri(String value, String label) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(label + "格式不正确");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException(label + "必须是 http(s) 完整地址");
        }
        return uri;
    }

    /** 从未配过推送的站点：传输列用占位默认满足 not-null，推送表单保存时会被真实值覆盖 */
    private PushConfig submissionOnlyConfig(Long siteId, LocalDateTime now) {
        PushConfig config = new PushConfig();
        config.setSiteId(siteId);
        config.setEnabled(false);
        config.setProtocol(PushProtocol.SFTP.name());
        config.setHost(SUBMISSION_ONLY_HOST);
        config.setPort(22);
        config.setUsername("-");
        config.setAuthType(AUTH_PASSWORD);
        config.setSitemapFileName(DEFAULT_SITEMAP_FILE_NAME);
        config.setCreatedAt(now);
        return config;
    }
```

同文件修改既有 `PushConfigServiceTest.setUp()` 一行：
`service = new PushConfigService(configRepository, logRepository, mock(SubmissionLogRepository.class), cipher);`

- [ ] **Step 5: 运行确认通过 + 回归**

`mvn -o test -Dtest=SubmissionSettingsTest+PushConfigServiceTest` → 全 PASS。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/ghgongjin/sitemap/service/push/ \
        src/test/java/io/github/ghgongjin/sitemap/service/push/SubmissionSettingsTest.java \
        src/test/java/io/github/ghgongjin/sitemap/service/push/PushConfigServiceTest.java
git commit -m "feat: 搜索引擎提交设置保存（校验/加密/凭据保留/脱敏视图）"
```

---

### Task 4: BaiduPushClient + 错误分类底座

**Files:**
- Create: `src/main/java/io/github/ghgongjin/sitemap/service/submission/SubmissionErrorCode.java`
- Create: `src/main/java/io/github/ghgongjin/sitemap/service/submission/SubmissionClientException.java`
- Create: `src/main/java/io/github/ghgongjin/sitemap/service/submission/BaiduPushClient.java`
- Test Create: `src/test/java/io/github/ghgongjin/sitemap/service/submission/BaiduPushClientTest.java`

**Interfaces:**
- Produces: `enum SubmissionErrorCode { BAIDU_TRANSPORT, BAIDU_REJECTED, GSC_TOKEN_REJECTED, GSC_NOT_A_SITE_USER, GSC_API_REJECTED, GSC_NETWORK, CONFIG_INVALID, CONFIG_DECRYPT_FAILED }`；`class SubmissionClientException extends Exception`（`errorCode()`，构造 `(SubmissionErrorCode, String)` 与 `(SubmissionErrorCode, String, Throwable)`）；`BaiduPushClient.DEFAULT_ENDPOINT`；`record BaiduPushClient.BaiduPushResponse(int success, int remain)`；`BaiduPushResponse push(String site, String token, List<String> urls) throws SubmissionClientException`；构造器 `(RestClient.Builder, String endpoint, ObjectMapper)`（Bean 装配用 @Value 默认）。
- Consumes: 无（客户端只吃显式参数）。

- [ ] **Step 1: 先写失败测试**

```java
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
    void shouldPostUrlsAsPlainTextWhenEndpointAccepts() {
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
    void shouldReportRemainMinusOneWhenBodyLacksRemain() {
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
```

注：若 push 声明 checked `SubmissionClientException`，测试中 `client.push(...)` 直调处给测试方法加 `throws Exception`。

- [ ] **Step 2: 运行确认失败**

- [ ] **Step 3: 实现**

`SubmissionErrorCode.java`：

```java
package io.github.ghgongjin.sitemap.service.submission;

/**
 * 搜索引擎提交错误分类（写入 submission_log.error_code，用于界面提示与排查）
 */
public enum SubmissionErrorCode {
    /** 到百度的网络/HTTP 传输失败 */
    BAIDU_TRANSPORT,
    /** 百度拒绝提交（业务错误码或响应不可解析） */
    BAIDU_REJECTED,
    /** GSC 授权端点拒绝 JWT 断言（服务账号 JSON 问题） */
    GSC_TOKEN_REJECTED,
    /** GSC 返回 403（服务账号未加入站点用户） */
    GSC_NOT_A_SITE_USER,
    /** GSC API 其他非 2xx */
    GSC_API_REJECTED,
    /** 到 Google 的网络不可达 */
    GSC_NETWORK,
    /** 配置缺失/非法（token 未配置、存量 JSON 损坏等） */
    CONFIG_INVALID,
    /** 凭据解密失败（密钥轮换后） */
    CONFIG_DECRYPT_FAILED
}
```

`SubmissionClientException.java`：

```java
package io.github.ghgongjin.sitemap.service.submission;

/**
 * 提交通道客户端异常：携带分类码，编排层据此写日志并翻译成人话
 */
public class SubmissionClientException extends Exception {

    private final SubmissionErrorCode errorCode;

    public SubmissionClientException(SubmissionErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public SubmissionClientException(SubmissionErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public SubmissionErrorCode errorCode() {
        return errorCode;
    }
}
```

`BaiduPushClient.java`：

```java
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
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -o test -Dtest=BaiduPushClientTest` → PASS（7 个）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/ghgongjin/sitemap/service/submission/ \
        src/test/java/io/github/ghgongjin/sitemap/service/submission/BaiduPushClientTest.java
git commit -m "feat: 百度主动推送客户端与提交错误分类"
```

---

### Task 5: GoogleSitemapClient（自签 JWT → OAuth token → sitemaps.submit）

**Files:**
- Create: `src/main/java/io/github/ghgongjin/sitemap/service/submission/GoogleSitemapClient.java`
- Test Create: `src/test/java/io/github/ghgongjin/sitemap/service/submission/GoogleSitemapClientTest.java`

**Interfaces:**
- Consumes: Task 2 `GoogleServiceAccount`、Task 4 `SubmissionClientException/ErrorCode` 与 `BaiduPushClient.percentEncode`（同包复用）。
- Produces: `GoogleSitemapClient.DEFAULT_BASE_URL`；`SCOPE = "https://www.googleapis.com/auth/indexing"`（package-private）；构造器 `(RestClient.Builder, String baseUrl, ObjectMapper)`；`void submitSitemap(GoogleServiceAccount account, String siteUrl, String sitemapUrl) throws SubmissionClientException`；package-private `String buildJwt(GoogleServiceAccount, long nowSeconds) throws SubmissionClientException`（供签名测试）。

- [ ] **Step 1: 先写失败测试**

```java
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
        client = new GoogleSitemapClient(builder, GoogleSitemapClient.DEFAULT_BASE_URL,
                new ObjectMapper());
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
                .andExpect(request -> {
                    String body = new String(request.getBody().readAllBytes(), StandardCharsets.UTF_8);
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
```

注：测试方法上的 `throws Exception` 因 `request.getBody()` 读取；`shouldThrowTokenRejectedWhenTokenEndpointReturns400` 不需要 throws 也可保留（签名多余 throws 允许）。

- [ ] **Step 2: 运行确认失败**

- [ ] **Step 3: 实现**

```java
package io.github.ghgongjin.sitemap.service.submission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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

    public GoogleSitemapClient(RestClient.Builder builder,
                               @Value("${sitemap.submission.gsc-base-url:" + DEFAULT_BASE_URL + "}")
                               String baseUrl,
                               ObjectMapper mapper) {
        this.restClient = builder.build();
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
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -o test -Dtest=GoogleSitemapClientTest` → PASS（6 个）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/ghgongjin/sitemap/service/submission/GoogleSitemapClient.java \
        src/test/java/io/github/ghgongjin/sitemap/service/submission/GoogleSitemapClientTest.java
git commit -m "feat: GSC sitemap 提交客户端（自签 JWT 授权，零新依赖）"
```

---

### Task 6: SearchEngineSubmissionService 编排

**Files:**
- Create: `src/main/java/io/github/ghgongjin/sitemap/service/submission/SubmissionOutcome.java`
- Create: `src/main/java/io/github/ghgongjin/sitemap/service/submission/SearchEngineSubmissionService.java`
- Test Create: `src/test/java/io/github/ghgongjin/sitemap/service/submission/SearchEngineSubmissionServiceTest.java`

**Interfaces:**
- Consumes: Task 1 实体/仓储与新列；Task 3 `PushConfig` 语义；Task 4/5 两个客户端与异常；`AutoSiteService.latestVersion(Long): Optional<AutoSiteVersion>`、`version(Long,int): Optional<AutoSiteVersion>`；`SiteDiffEngine.diff(prev,new)`；`IndexNowClient.extractUrls(String): List<String>`（复用其提取逻辑）；`CredentialCipher.decrypt`。
- Produces: `record SubmissionOutcome(boolean success, boolean skipped, String detail)` + 工厂 `skipped/success/failure`；`SearchEngineSubmissionService.submit(Long siteId): SubmissionOutcome`；package-private `List<String> candidateUrls(Long siteId, AutoSiteVersion latest)` 与 `BaiduUrls baiduUrls(String baiduSite, List<String> candidates)`（`record BaiduUrls(List<String> batch, int filteredOut)`，package-private，供单测直调）；常量 `BAIDU_MAX_URLS = 2000`、`KEEP_LOGS = 50`。

- [ ] **Step 1: 先写失败测试**

```java
package io.github.ghgongjin.sitemap.service.submission;

import io.github.ghgongjin.sitemap.entity.AutoSiteVersion;
import io.github.ghgongjin.sitemap.entity.PushConfig;
import io.github.ghgongjin.sitemap.entity.SubmissionLog;
import io.github.ghgongjin.sitemap.repository.AutoSiteVersionRepository;
import io.github.ghgongjin.sitemap.repository.PushConfigRepository;
import io.github.ghgongjin.sitemap.repository.SubmissionLogRepository;
import io.github.ghgongjin.sitemap.service.AutoSiteService;
import io.github.ghgongjin.sitemap.service.CredentialCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchEngineSubmissionServiceTest {

    private static final Long SITE_ID = 1L;

    @TempDir
    Path tempDir;

    private PushConfigRepository configRepository;
    private SubmissionLogRepository logRepository;
    private AutoSiteService autoSiteService;
    private CredentialCipher cipher;
    private BaiduPushClient baiduClient;
    private GoogleSitemapClient gscClient;
    private SearchEngineSubmissionService service;

    @BeforeEach
    void setUp() {
        configRepository = mock(PushConfigRepository.class);
        logRepository = mock(SubmissionLogRepository.class);
        autoSiteService = mock(AutoSiteService.class);
        cipher = new CredentialCipher("", tempDir.resolve("push.key").toString());
        baiduClient = mock(BaiduPushClient.class);
        gscClient = mock(GoogleSitemapClient.class);
        service = new SearchEngineSubmissionService(configRepository, logRepository,
                autoSiteService, cipher, baiduClient, gscClient);
        when(logRepository.save(any(SubmissionLog.class))).thenAnswer(inv -> inv.getArgument(0));
        when(logRepository.findBySiteIdOrderByIdDesc(SITE_ID)).thenReturn(List.of());
    }

    private static AutoSiteVersion version(int number, String... urls) {
        AutoSiteVersion entity = new AutoSiteVersion();
        entity.setSiteId(SITE_ID);
        entity.setVersionNumber(number);
        StringBuilder xml = new StringBuilder(
                "<urlset><url><loc>https://example.com/keep</loc><lastmod>2026-01-01</lastmod></url>");
        for (String url : urls) {
            xml.append("<url><loc>").append(url).append("</loc><lastmod>2026-01-02</lastmod></url>");
        }
        entity.setSitemapXml(xml.append("</urlset>").toString());
        return entity;
    }

    private PushConfig baiduConfig() {
        PushConfig config = new PushConfig();
        config.setSiteId(SITE_ID);
        config.setBaiduEnabled(true);
        config.setBaiduSite("https://example.com");
        config.setBaiduTokenEnc(cipher.encrypt("tok123456"));
        return config;
    }

    private PushConfig gscConfig() {
        PushConfig config = new PushConfig();
        config.setSiteId(SITE_ID);
        config.setGscEnabled(true);
        config.setGscSiteUrl("sc-domain:example.com");
        config.setGscSitemapUrl("https://example.com/sitemap.xml");
        config.setGscServiceAccountJsonEnc(cipher.encrypt(validJson()));
        return config;
    }

    private static String validJson() {
        try {
            var generator = java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + java.util.Base64.getMimeEncoder(64, new byte[] {'\n'})
                            .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----";
            return "{\"type\":\"service_account\",\"client_email\":\"sa@p.iam.gserviceaccount.com\","
                    + "\"token_uri\":\"https://oauth2.googleapis.com/token\",\"private_key\":"
                    + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(pem) + "}";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void shouldSubmitOnlyAddedAndChangedUrlsWhenPreviousExists() throws Exception {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(baiduConfig()));
        when(autoSiteService.latestVersion(SITE_ID))
                .thenReturn(Optional.of(version(2, "https://example.com/new", "https://example.com/mod")));
        when(autoSiteService.version(SITE_ID, 1)).thenReturn(Optional.of(
                version(1, "https://example.com/mod")));
        when(baiduClient.push(anyString(), anyString(), any())).thenReturn(
                new BaiduPushClient.BaiduPushResponse(1, 99));

        SubmissionOutcome outcome = service.submit(SITE_ID);

        assertThat(outcome.success()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(baiduClient).push(eq("https://example.com"), eq("tok123456"), captor.capture());
        assertThat(captor.getValue()).containsExactly("https://example.com/new");
        ArgumentCaptor<SubmissionLog> log = ArgumentCaptor.forClass(SubmissionLog.class);
        verify(logRepository).save(log.capture());
        assertThat(log.getValue().getChannel()).isEqualTo(SubmissionLog.CHANNEL_BAIDU);
        assertThat(log.getValue().getStatus()).isEqualTo(SubmissionLog.STATUS_SUCCESS);
        assertThat(log.getValue().getDetail()).contains("接收 1").contains("剩余配额 99");
    }

    @Test
    void shouldFallBackToFullUrlsWhenPreviousVersionMissing() throws Exception {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(baiduConfig()));
        when(autoSiteService.latestVersion(SITE_ID))
                .thenReturn(Optional.of(version(3, "https://example.com/a", "https://other.org/b")));
        when(autoSiteService.version(SITE_ID, 2)).thenReturn(Optional.empty());
        when(baiduClient.push(anyString(), anyString(), any())).thenReturn(
                new BaiduPushClient.BaiduPushResponse(1, 99));

        service.submit(SITE_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(baiduClient).push(anyString(), anyString(), captor.capture());
        assertThat(captor.getValue()).containsExactly("https://example.com/keep", "https://example.com/a");
    }

    @Test
    void shouldFilterForeignHostsAndCountThemInDetail() throws Exception {
        SearchEngineSubmissionService.BaiduUrls urls =
                service.baiduUrls("https://example.com", List.of(
                        "https://example.com/a", "http://example.com/b", "https://cdn.example.com/c",
                        "ftp://example.com/d", "not a url", "https://EXAMPLE.COM/e"));

        assertThat(urls.batch()).containsExactly("https://example.com/a", "http://example.com/b",
                "https://EXAMPLE.COM/e");
        assertThat(urls.filteredOut()).isEqualTo(3);
    }

    @Test
    void shouldTruncateBatchToMaxUrls() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < SearchEngineSubmissionService.BAIDU_MAX_URLS + 5; i++) {
            many.add("https://example.com/p" + i);
        }
        SearchEngineSubmissionService.BaiduUrls urls = service.baiduUrls("https://example.com", many);
        assertThat(urls.batch()).hasSize(SearchEngineSubmissionService.BAIDU_MAX_URLS);
        assertThat(urls.filteredOut()).isZero();
    }

    @Test
    void shouldSkipWithoutLogWhenBothChannelsDisabledOrUnconfigured() {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());
        assertThat(service.submit(SITE_ID).skipped()).isTrue();

        PushConfig disabled = new PushConfig();
        disabled.setSiteId(SITE_ID);
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(disabled));
        assertThat(service.submit(SITE_ID).skipped()).isTrue();

        verify(logRepository, never()).save(any());
    }

    @Test
    void shouldStillSubmitGscWhenBaiduFailsAndReportFailure() throws Exception {
        PushConfig both = baiduConfig();
        both.setGscEnabled(true);
        both.setGscSiteUrl("sc-domain:example.com");
        both.setGscSitemapUrl("https://example.com/sitemap.xml");
        both.setGscServiceAccountJsonEnc(cipher.encrypt(validJson()));
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(both));
        when(autoSiteService.latestVersion(SITE_ID)).thenReturn(Optional.of(version(1)));
        when(baiduClient.push(anyString(), anyString(), any()))
                .thenThrow(new SubmissionClientException(SubmissionErrorCode.BAIDU_REJECTED, "百度拒绝"));

        SubmissionOutcome outcome = service.submit(SITE_ID);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.detail()).contains("百度");
        verify(gscClient).submitSitemap(any(), eq("sc-domain:example.com"),
                eq("https://example.com/sitemap.xml"));
        ArgumentCaptor<SubmissionLog> log = ArgumentCaptor.forClass(SubmissionLog.class);
        verify(logRepository, org.mockito.Mockito.times(2)).save(log.capture());
        assertThat(log.getAllValues()).extracting(SubmissionLog::getStatus)
                .containsExactly(SubmissionLog.STATUS_FAILED, SubmissionLog.STATUS_SUCCESS);
        assertThat(log.getAllValues().get(0).getErrorCode()).isEqualTo("BAIDU_REJECTED");
    }

    @Test
    void shouldRecordDecryptFailureAsConfigDecryptFailed() {
        PushConfig config = baiduConfig();
        config.setBaiduTokenEnc(cipher.encrypt("x"));
        // 换一把钥匙：模拟 master key 轮换后存量密文不可解
        service = new SearchEngineSubmissionService(configRepository, logRepository,
                autoSiteService, new CredentialCipher("", tempDir.resolve("other.key").toString()),
                baiduClient, gscClient);
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(config));
        when(autoSiteService.latestVersion(SITE_ID)).thenReturn(Optional.of(version(1)));

        SubmissionOutcome outcome = service.submit(SITE_ID);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.detail()).contains("凭据无法解密");
    }

    @Test
    void shouldTrimLogsBeyondFiftyPerSite() {
        List<SubmissionLog> fiftyOne = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            SubmissionLog entry = new SubmissionLog();
            entry.setSiteId(SITE_ID);
            fiftyOne.add(entry);
        }
        when(logRepository.findBySiteIdOrderByIdDesc(SITE_ID)).thenReturn(fiftyOne);
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(baiduConfig()));
        when(autoSiteService.latestVersion(SITE_ID)).thenReturn(Optional.of(version(1)));
        when(baiduClient.push(anyString(), anyString(), any()))
                .thenReturn(new BaiduPushClient.BaiduPushResponse(1, 99));

        service.submit(SITE_ID);

        verify(logRepository).deleteAll(org.mockito.ArgumentMatchers.argThat(
                list -> list.size() == 1));
    }
}
```

- [ ] **Step 2: 运行确认失败**

- [ ] **Step 3: 实现**

`SubmissionOutcome.java`：

```java
package io.github.ghgongjin.sitemap.service.submission;

/**
 * 一次多通道提交的结果（skipped：未配置/无版本/无同域 URL，不产生日志）
 */
public record SubmissionOutcome(boolean success, boolean skipped, String detail) {

    public static SubmissionOutcome skipped(String detail) {
        return new SubmissionOutcome(false, true, detail);
    }

    public static SubmissionOutcome success(String detail) {
        return new SubmissionOutcome(true, false, detail);
    }

    public static SubmissionOutcome failure(String detail) {
        return new SubmissionOutcome(false, false, detail);
    }
}
```

`SearchEngineSubmissionService.java`：

```java
package io.github.ghgongjin.sitemap.service.submission;

import io.github.ghgongjin.sitemap.entity.AutoSiteVersion;
import io.github.ghgongjin.sitemap.entity.PushConfig;
import io.github.ghgongjin.sitemap.entity.SubmissionLog;
import io.github.ghgongjin.sitemap.repository.PushConfigRepository;
import io.github.ghgongjin.sitemap.repository.SubmissionLogRepository;
import io.github.ghgongjin.sitemap.service.AutoSiteService;
import io.github.ghgongjin.sitemap.service.CredentialCipher;
import io.github.ghgongjin.sitemap.service.SiteDiffEngine;
import io.github.ghgongjin.sitemap.service.push.IndexNowClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 搜索引擎提交编排：百度只提 diff 新增/修改（首版全量、外域过滤、2000 截断），
 * GSC 提交 sitemap 地址；每通道一次，一切失败止步于 submission_log。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchEngineSubmissionService {

    static final int BAIDU_MAX_URLS = 2000;
    static final int KEEP_LOGS = 50;
    static final int DETAIL_MAX_LENGTH = 512;

    private final PushConfigRepository configRepository;
    private final SubmissionLogRepository logRepository;
    private final AutoSiteService autoSiteService;
    private final CredentialCipher credentialCipher;
    private final BaiduPushClient baiduPushClient;
    private final GoogleSitemapClient googleSitemapClient;

    /** 本批将提交百度的 URL 与被过滤的外域数量 */
    record BaiduUrls(List<String> batch, int filteredOut) {
    }

    private record ChannelResult(boolean executed, boolean success,
                                 SubmissionErrorCode errorCode, String detail) {
        static final ChannelResult SKIPPED = new ChannelResult(false, false, null, null);
    }

    public SubmissionOutcome submit(Long siteId) {
        Optional<PushConfig> configOpt = configRepository.findBySiteId(siteId);
        if (configOpt.isEmpty()) {
            return SubmissionOutcome.skipped("尚未配置搜索引擎提交");
        }
        PushConfig config = configOpt.get();
        if (!config.isBaiduEnabled() && !config.isGscEnabled()) {
            return SubmissionOutcome.skipped("未启用任何提交通道");
        }
        Optional<AutoSiteVersion> versionOpt = autoSiteService.latestVersion(siteId);
        if (versionOpt.isEmpty()) {
            return SubmissionOutcome.skipped("暂无已生成的站点地图版本");
        }
        AutoSiteVersion version = versionOpt.get();
        long startedAt = System.nanoTime();
        int executed = 0;
        List<String> failures = new ArrayList<>();
        if (config.isBaiduEnabled()) {
            ChannelResult result = submitBaidu(config, version);
            if (result.executed()) {
                executed++;
                record(SubmissionLog.CHANNEL_BAIDU, version, result, startedAt);
                if (!result.success()) {
                    failures.add("百度：" + result.detail());
                }
            }
        }
        if (config.isGscEnabled()) {
            ChannelResult result = submitGsc(config);
            if (result.executed()) {
                executed++;
                record(SubmissionLog.CHANNEL_GSC, version, result, startedAt);
                if (!result.success()) {
                    failures.add("GSC：" + result.detail());
                }
            }
        }
        if (executed == 0) {
            return SubmissionOutcome.skipped("没有可提交的 URL（检查站点与凭据配置）");
        }
        if (failures.isEmpty()) {
            return SubmissionOutcome.success("已提交 " + executed + " 个通道");
        }
        return SubmissionOutcome.failure(String.join("；", failures));
    }

    private ChannelResult submitBaidu(PushConfig config, AutoSiteVersion version) {
        String token;
        try {
            token = credentialCipher.decrypt(config.getBaiduTokenEnc());
        } catch (IllegalStateException e) {
            return new ChannelResult(true, false, SubmissionErrorCode.CONFIG_DECRYPT_FAILED,
                    "凭据无法解密，请重新保存提交设置");
        }
        if (token == null || token.isBlank()) {
            return new ChannelResult(true, false, SubmissionErrorCode.CONFIG_INVALID,
                    "百度 token 未配置");
        }
        BaiduUrls urls = baiduUrls(config.getBaiduSite(), candidateUrls(config.getSiteId(), version));
        if (urls.batch().isEmpty()) {
            return ChannelResult.SKIPPED;
        }
        try {
            BaiduPushClient.BaiduPushResponse response =
                    baiduPushClient.push(config.getBaiduSite(), token, urls.batch());
            if (response == null) {
                return new ChannelResult(true, false, SubmissionErrorCode.BAIDU_REJECTED,
                        "百度响应为空");
            }
            String remain = response.remain() < 0 ? "未知" : String.valueOf(response.remain());
            return new ChannelResult(true, true, null,
                    "接收 " + response.success() + "，剩余配额 " + remain
                            + "，外域已过滤 " + urls.filteredOut()
                            + (urls.batch().size() >= BAIDU_MAX_URLS ? "，本批已截断 " + BAIDU_MAX_URLS : ""));
        } catch (SubmissionClientException e) {
            return new ChannelResult(true, false, e.errorCode(), e.getMessage());
        }
    }

    private ChannelResult submitGsc(PushConfig config) {
        String json;
        try {
            json = credentialCipher.decrypt(config.getGscServiceAccountJsonEnc());
        } catch (IllegalStateException e) {
            return new ChannelResult(true, false, SubmissionErrorCode.CONFIG_DECRYPT_FAILED,
                    "凭据无法解密，请重新保存提交设置");
        }
        if (json == null || json.isBlank()) {
            return new ChannelResult(true, false, SubmissionErrorCode.CONFIG_INVALID,
                    "服务账号 JSON 未配置");
        }
        GoogleServiceAccount account;
        try {
            account = GoogleServiceAccount.parse(json);
        } catch (IllegalArgumentException e) {
            return new ChannelResult(true, false, SubmissionErrorCode.CONFIG_INVALID,
                    "已存服务账号 JSON 无效：" + e.getMessage());
        }
        if (isBlank(config.getGscSiteUrl()) || isBlank(config.getGscSitemapUrl())) {
            return new ChannelResult(true, false, SubmissionErrorCode.CONFIG_INVALID,
                    "GSC 站点地址或 sitemap 公开 URL 未配置");
        }
        try {
            googleSitemapClient.submitSitemap(account, config.getGscSiteUrl(), config.getGscSitemapUrl());
            return new ChannelResult(true, true, null, "GSC 已受理：" + config.getGscSitemapUrl());
        } catch (SubmissionClientException e) {
            String detail = e.errorCode() == SubmissionErrorCode.GSC_NOT_A_SITE_USER
                    ? e.getMessage() + "（账号：" + account.clientEmail() + "）"
                    : e.getMessage();
            return new ChannelResult(true, false, e.errorCode(), detail);
        }
    }

    /** 本版 vs 上版的 新增+修改；首版、上版缺失或 XML 不可解析时退化为全量 */
    List<String> candidateUrls(Long siteId, AutoSiteVersion latest) {
        List<String> full = IndexNowClient.extractUrls(latest.getSitemapXml());
        if (latest.getVersionNumber() <= 1) {
            return full;
        }
        AutoSiteVersion previous = autoSiteService.version(siteId, latest.getVersionNumber() - 1)
                .orElse(null);
        if (previous == null) {
            return full;
        }
        try {
            SiteDiffEngine.SiteDiff diff = SiteDiffEngine.diff(
                    previous.getSitemapXml(), latest.getSitemapXml());
            return Stream.concat(diff.added().stream(), diff.changed().stream())
                    .distinct().sorted().toList();
        } catch (RuntimeException e) {
            log.warn("提交 diff 计算失败，退化为全量：siteId={}，{}", siteId, e.getMessage());
            return full;
        }
    }

    /** 仅保留 host 与 baidu_site 完全一致的 http(s) URL，截断到 BAIDU_MAX_URLS */
    BaiduUrls baiduUrls(String baiduSite, List<String> candidates) {
        String host = URI.create(baiduSite).getHost();
        List<String> sameHost = new ArrayList<>();
        int filteredOut = 0;
        for (String url : candidates) {
            if (isSameSiteHttpUrl(url, host)) {
                sameHost.add(url);
            } else {
                filteredOut++;
            }
        }
        return new BaiduUrls(sameHost.stream().limit(BAIDU_MAX_URLS).toList(), filteredOut);
    }

    static boolean isSameSiteHttpUrl(String url, String host) {
        try {
            URI uri = URI.create(url);
            return uri.isAbsolute()
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && host != null && host.equalsIgnoreCase(uri.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void record(String channel, AutoSiteVersion version, ChannelResult result, long startedAt) {
        SubmissionLog entry = new SubmissionLog();
        entry.setSiteId(version.getSiteId());
        entry.setVersionNumber(version.getVersionNumber());
        entry.setChannel(channel);
        entry.setStatus(result.success() ? SubmissionLog.STATUS_SUCCESS : SubmissionLog.STATUS_FAILED);
        entry.setErrorCode(result.errorCode() == null ? null : result.errorCode().name());
        entry.setDetail(trimDetail(result.detail()));
        entry.setDurationMs((System.nanoTime() - startedAt) / 1_000_000);
        entry.setCreatedAt(LocalDateTime.now());
        logRepository.save(entry);
        trimLogs(version.getSiteId());
    }

    private void trimLogs(Long siteId) {
        List<SubmissionLog> all = logRepository.findBySiteIdOrderByIdDesc(siteId);
        if (all.size() > KEEP_LOGS) {
            logRepository.deleteAll(all.subList(KEEP_LOGS, all.size()));
        }
    }

    private String trimDetail(String message) {
        if (message == null || message.isBlank()) {
            return "未知结果";
        }
        String trimmed = message.trim();
        return trimmed.length() <= DETAIL_MAX_LENGTH ? trimmed : trimmed.substring(0, DETAIL_MAX_LENGTH);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -o test -Dtest=SearchEngineSubmissionServiceTest` → PASS（8 个）。
`mvn -o test -Dtest=BaiduPushClientTest+GoogleSitemapClientTest+GoogleServiceAccountTest` 回归。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/ghgongjin/sitemap/service/submission/SubmissionOutcome.java \
        src/main/java/io/github/ghgongjin/sitemap/service/submission/SearchEngineSubmissionService.java \
        src/test/java/io/github/ghgongjin/sitemap/service/submission/SearchEngineSubmissionServiceTest.java
git commit -m "feat: 搜索引擎提交编排（diff 选取/外域过滤/截断/日志/异常隔离）"
```

---

### Task 7: 接线 — AutoSiteUpdater 挂点 + Controller 端点与详情模型

**Files:**
- Modify: `src/main/java/io/github/ghgongjin/sitemap/service/AutoSiteUpdater.java`
- Modify: `src/main/java/io/github/ghgongjin/sitemap/controller/AutoSiteController.java`
- Test Modify: `src/test/java/io/github/ghgongjin/sitemap/service/AutoSiteUpdaterTest.java`（构造器行 + 新用例）
- Test Modify: `src/test/java/io/github/ghgongjin/sitemap/controller/AutoSiteControllerTest.java`（构造器行 + 新用例）

**Interfaces:**
- Consumes: Task 3 `pushConfigService.saveSubmission/submissionView/submissionLogs`；Task 6 `SearchEngineSubmissionService.submit(Long): SubmissionOutcome`。
- Produces: 端点 `POST /auto/{id}/submission/settings`（参数名 `baiduEnabled,baiduSite,baiduToken,gscEnabled,gscSiteUrl,gscSitemapUrl,gscServiceAccountJson`）、`POST /auto/{id}/submission/run`；detail 模型属性 `submission`（`SubmissionView|null`）与 `submissionLogs`（`List<SubmissionLog>`）；`AutoSiteUpdater` 新构造器第 6 参 `SearchEngineSubmissionService`（在 `sitemapPushService` 之后、`events` 之前）。

- [ ] **Step 1: AutoSiteUpdater 测试先行**

`AutoSiteUpdaterTest`：字段区加 `private SearchEngineSubmissionService submissionService;`，setUp 中
`submissionService = mock(SearchEngineSubmissionService.class);` 并把构造调用改为
`new AutoSiteUpdater(autoSiteService, generator, progressService, seoReportService, pushService, submissionService, events)`
（以该测试现有局部变量名为准逐一对位）。新用例：

```java
    @Test
    void shouldSubmitAfterUpdateWhenPushSucceeded() {
        // 复用本类既有的"更新成功"Given 桩（照 shouldPushAfterUpdate 模式）
        // When 调用 update(site) 成功路径
        // Then
        verify(submissionService).submit(1L);
    }

    @Test
    void shouldKeepUpdateSuccessWhenSubmissionThrows() {
        doThrow(new RuntimeException("submit down")).when(submissionService).submit(1L);
        // 复用"更新成功"Given 桩
        assertThat(updater.update(site)).isTrue();
    }

    @Test
    void shouldNotSubmitWhenUpdateFailed() {
        // 复用"抓取抛异常→更新失败"Given 桩
        verify(submissionService, never()).submit(anyLong());
    }
```

实现者注：三个用例的 Given 段照抄同文件既有用例 `verify(pushService).push(1L)` / `doThrow(...).when(pushService).push(1L)` / `verify(pushService, never()).push(...)` 所在测试的 Given 与调用，只把断言目标换成 submissionService。

- [ ] **Step 2: 运行确认失败**（构造器参数数不匹配，编译错误）

- [ ] **Step 3: AutoSiteUpdater 实现**

字段区 `sitemapPushService` 之后加：

```java
    private final SearchEngineSubmissionService searchEngineSubmissionService;
```

`update(site)` 成功分支 `pushLatestVersion(site);` 之后加一行 `submitToSearchEngines(site);`；新方法照 `pushLatestVersion` 同构：

```java
    /**
     * 搜索引擎提交与更新主流程完全隔离：提交异常不影响更新结果与站点状态
     */
    private void submitToSearchEngines(AutoSite site) {
        try {
            SubmissionOutcome outcome = searchEngineSubmissionService.submit(site.getId());
            if (!outcome.success() && !outcome.skipped()) {
                log.warn("自动更新后搜索引擎提交失败：{}，原因：{}", site.getUrl(), outcome.detail());
            }
        } catch (Exception e) {
            log.warn("自动更新后搜索引擎提交异常：{}，原因：{}", site.getUrl(), e.getMessage());
        }
    }
```

import：`io.github.ghgongjin.sitemap.service.submission.SearchEngineSubmissionService`、`...submission.SubmissionOutcome`。

Run: `mvn -o test -Dtest=AutoSiteUpdaterTest` → PASS。

- [ ] **Step 4: Controller 测试先行**

`AutoSiteControllerTest`：mock 字段与构造调用尾部追加 `mock(SearchEngineSubmissionService.class)`（控制器用 `@RequiredArgsConstructor`，新依赖声明在最后）。新用例（Given 桩模式照抄同文件 push/settings 既有测试）：

```java
    @Test
    void shouldSaveSubmissionSettingsAndFlashWhenValid() throws Exception {
        mockMvc.perform(post("/auto/1/submission/settings")
                        .param("baiduEnabled", "true")
                        .param("baiduSite", "https://example.com")
                        .param("baiduToken", "tok123456")
                        .param("gscEnabled", "false")
                        .header("Authorization", basicAuth()))  // 照本类既有登录头构造方式
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flash", "auto.submission.flash.saved"));
        verify(pushConfigService).saveSubmission(eq(1L), argThat(settings ->
                settings.baiduEnabled() && "https://example.com".equals(settings.baiduSite())));
    }

    @Test
    void shouldFlashRawErrorWhenSubmissionValidationFails() throws Exception {
        doThrow(new IllegalArgumentException("百度站点必须是 https://example.com 形式（不含端口与路径）"))
                .when(pushConfigService).saveSubmission(anyLong(), any(SubmissionSettings.class));
        mockMvc.perform(post("/auto/1/submission/settings")
                        .param("baiduEnabled", "true").header("Authorization", basicAuth()))
                .andExpect(redirectedUrl("/auto/1"))
                .andExpect(flash().attribute("flashError",
                        "百度站点必须是 https://example.com 形式（不含端口与路径）"));
    }

    @Test
    void shouldFlashSubmittedWhenRunSubmissionSucceeds() throws Exception {
        when(submissionService.submit(1L)).thenReturn(SubmissionOutcome.success("已提交 2 个通道"));
        mockMvc.perform(post("/auto/1/submission/run").header("Authorization", basicAuth()))
                .andExpect(redirectedUrl("/auto/1"))
                .andExpect(flash().attribute("flash", "auto.submission.flash.submitted"));
    }

    @Test
    void shouldFlashErrorDetailWhenRunSubmissionFails() throws Exception {
        when(submissionService.submit(1L)).thenReturn(SubmissionOutcome.failure("百度：配额已用尽"));
        mockMvc.perform(post("/auto/1/submission/run").header("Authorization", basicAuth()))
                .andExpect(flash().attribute("flashError", "百度：配额已用尽"));
    }

    @Test
    void shouldSkipFlashErrorWhenRunSubmissionSkipped() throws Exception {
        when(submissionService.submit(1L)).thenReturn(SubmissionOutcome.skipped("尚未配置搜索引擎提交"));
        mockMvc.perform(post("/auto/1/submission/run").header("Authorization", basicAuth()))
                .andExpect(flash().attribute("flashError", "尚未配置搜索引擎提交"));
    }

    @Test
    void shouldReturn404WhenSubmissionRunOnForeignSite() throws Exception {
        // Given：requireOwned 语义（照本类既有"他人站点 404"用例的桩，如 findOwned 返回空）
        mockMvc.perform(post("/auto/999/submission/run").header("Authorization", basicAuth()))
                .andExpect(status().isNotFound());
        verifyNoInteractions(submissionService);
    }
```

实现者注：`basicAuth()`/登录头与本类既有认证方式对齐（若是 SecurityMockMvc 或直注 principal，照现状改）；他人 404 用例的 siteId 与桩照抄本类既有 404 测试。`detail()` 模型断言加在既有 detail 测试里：`model().attributeExists("submission", "submissionLogs")`（若既有断言用 jsoup 渲染则加 `#submissionForm` 存在断言——渲染断言放 Task 8 模板就绪后）。

- [ ] **Step 5: Controller 实现**

字段 `sitemapPushService` 后加（`@RequiredArgsConstructor` 顺序）：

```java
    private final SearchEngineSubmissionService searchEngineSubmissionService;
```

`detail()` 中 `notify...` 属性之后加：

```java
        model.addAttribute("submission", pushConfigService.submissionView(id).orElse(null));
        model.addAttribute("submissionLogs", pushConfigService.submissionLogs(id));
```

新端点（放在 push/run 端点之后）：

```java
    @PostMapping("/{id}/submission/settings")
    public String saveSubmissionSettings(@PathVariable Long id,
                                         @RequestParam(value = "baiduEnabled", defaultValue = "false")
                                         boolean baiduEnabled,
                                         @RequestParam(value = "baiduSite", defaultValue = "") String baiduSite,
                                         @RequestParam(value = "baiduToken", defaultValue = "") String baiduToken,
                                         @RequestParam(value = "gscEnabled", defaultValue = "false")
                                         boolean gscEnabled,
                                         @RequestParam(value = "gscSiteUrl", defaultValue = "") String gscSiteUrl,
                                         @RequestParam(value = "gscSitemapUrl", defaultValue = "") String gscSitemapUrl,
                                         @RequestParam(value = "gscServiceAccountJson", defaultValue = "")
                                         String gscServiceAccountJson,
                                         RedirectAttributes redirect) {
        requireOwned(id, SecurityUtils.currentUserId());
        SubmissionSettings settings = new SubmissionSettings(baiduEnabled, baiduSite, baiduToken,
                gscEnabled, gscSiteUrl, gscSitemapUrl, gscServiceAccountJson);
        return mutate(redirect, "auto.submission.flash.saved", detailPath(id),
                () -> pushConfigService.saveSubmission(id, settings));
    }

    @PostMapping("/{id}/submission/run")
    public String runSubmission(@PathVariable Long id, RedirectAttributes redirect) {
        requireOwned(id, SecurityUtils.currentUserId());
        SubmissionOutcome outcome = searchEngineSubmissionService.submit(id);
        if (outcome.success()) {
            redirect.addFlashAttribute("flash", "auto.submission.flash.submitted");
        } else {
            redirect.addFlashAttribute("flashError", outcome.detail());
        }
        return "redirect:" + detailPath(id);
    }
```

import：`service.push.SubmissionSettings`、`service.submission.SearchEngineSubmissionService`、`service.submission.SubmissionOutcome`。

- [ ] **Step 6: 运行确认通过 + 回归**

`mvn -o test -Dtest=AutoSiteControllerTest+AutoSiteUpdaterTest` → PASS。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/ghgongjin/sitemap/service/AutoSiteUpdater.java \
        src/main/java/io/github/ghgongjin/sitemap/controller/AutoSiteController.java \
        src/test/java/io/github/ghgongjin/sitemap/service/AutoSiteUpdaterTest.java \
        src/test/java/io/github/ghgongjin/sitemap/controller/AutoSiteControllerTest.java
git commit -m "feat: 自动更新与详情页接线搜索引擎提交（挂点+端点+模型）"
```

---

### Task 8: UI 区块 + 双语文案

**Files:**
- Modify: `src/main/resources/templates/auto-detail.html`（push 区块 `</div>` 之后、通知区块之前插入）
- Modify: `src/main/resources/messages.properties` / `messages_en.properties`
- Test Modify: `src/test/java/io/github/ghgongjin/sitemap/controller/AutoSiteControllerTest.java`（detail 渲染断言）

**Interfaces:**
- Consumes: Task 7 的模型属性 `submission` / `submissionLogs`、两个表单 action；push-panel 既有 CSS 类（`list-panel push-panel push-group push-grid field field-label push-note push-switches sel*`）与 checkbox 显隐 JS 模式（`js-indexnow-key`）。
- Produces: DOM 锚点 `#submissionForm`、`#submissionRunForm`、`.js-baidu-fields`、`.js-gsc-fields`、`#submissionLogTable`；i18n key 见 Step 3。

- [ ] **Step 1: 先写失败的渲染断言**

在 `AutoSiteControllerTest` 既有 detail 渲染测试旁新增：

```java
    @Test
    void shouldRenderSubmissionPanelWhenDetailLoaded() throws Exception {
        // Given：照本类既有 detail 渲染测试的桩（owned site + pushConfig 等），
        // 追加 when(pushConfigService.submissionView(1L)).thenReturn(Optional.of(new SubmissionView(
        //         true, "https://example.com", true, false, "", "", false, null)));
        //      when(pushConfigService.submissionLogs(1L)).thenReturn(List.of());
        MvcResult result = mockMvc.perform(get("/auto/1").header("Authorization", 既有登录头))
                .andExpect(status().isOk())
                .andReturn();
        Document page = Jsoup.parse(result.getResponse().getContentAsString());

        assertThat(page.select("form#submissionForm")).hasSize(1);
        assertThat(page.select("form#submissionRunForm")).hasSize(1);
        assertThat(page.getElementById("submissionBaiduSite")).isNotNull();
        assertThat(page.select("textarea[name=gscServiceAccountJson]")).hasSize(1);
        // 凭据永不回显：token/JSON 明文不出现在渲染 HTML
        assertThat(page.toString()).doesNotContain("v1:");
    }
```

Run: `mvn -o test -Dtest=AutoSiteControllerTest` → 新用例 FAIL（模板无此区块）。

- [ ] **Step 2: 模板区块**

`auto-detail.html`：在推送 panel 结束与通知 panel 开始之间插入（结构完全克隆 push-panel 的类名与 th:with 模式；JS 显隐照 `js-indexnow-key` 的监听器写法加 `js-baidu-fields` / `js-gsc-fields` 两组）：

```html
  <div class="list-panel push-panel" id="submissionPanel"
       th:with="sBaidu=${submission != null && submission.baiduEnabled()},
                sSite=${submission != null && submission.baiduSite != null ? submission.baiduSite : ''},
                sHasTok=${submission != null && submission.hasBaiduToken},
                sGsc=${submission != null && submission.gscEnabled},
                sProp=${submission != null && submission.gscSiteUrl != null ? submission.gscSiteUrl : ''},
                sSmUri=${submission != null && submission.gscSitemapUrl != null ? submission.gscSitemapUrl : ''},
                sHasJson=${submission != null && submission.hasGscJson},
                sEmail=${submission != null ? submission.gscClientEmail : null}">
    <div class="panel-head">
      <h3 th:text="#{auto.submission.title}">搜索引擎提交</h3>
      <span class="badge" th:if="${sBaidu or sGsc}" th:text="#{auto.submission.on}">提交已启用</span>
      <span class="badge badge-off" th:unless="${sBaidu or sGsc}" th:text="#{auto.submission.off}">未启用提交</span>
    </div>
    <form id="submissionForm" method="post"
          th:action="@{/auto/{id}/submission/settings(id=${site.id})}" novalidate>
      <div class="push-switches">
        <label class="switch">
          <input type="checkbox" name="baiduEnabled" th:checked="${sBaidu}">
          <b th:text="#{auto.submission.baidu.enabled}">启用百度提交</b>
          <i th:text="#{auto.submission.baidu.enabled.d}">推送成功后提交新增与修改的 URL</i>
        </label>
      </div>
      <div class="push-grid js-baidu-fields" th:attr="hidden=${!sBaidu} ? 'hidden' : null">
        <div class="field">
          <label class="field-label" for="submissionBaiduSite" th:text="#{auto.submission.baidu.site}">百度站点</label>
          <input id="submissionBaiduSite" type="text" name="baiduSite" autocomplete="off"
                 th:value="${sSite}" th:placeholder="#{auto.submission.baidu.site.ph}"
                 placeholder="https://example.com">
        </div>
        <div class="field">
          <label class="field-label" for="submissionBaiduToken" th:text="#{auto.submission.baidu.token}">推送 token</label>
          <input id="submissionBaiduToken" type="password" name="baiduToken" autocomplete="new-password"
                 th:placeholder="${sHasTok} ? #{auto.submission.baidu.token.keep} : #{auto.submission.baidu.token.hint}"
                 placeholder="百度搜索资源平台普通收录处获取">
        </div>
      </div>
      <div class="push-switches">
        <label class="switch">
          <input type="checkbox" name="gscEnabled" th:checked="${sGsc}">
          <b th:text="#{auto.submission.gsc.enabled}">启用 GSC 提交</b>
          <i th:text="#{auto.submission.gsc.enabled.d}">推送成功后向 Google 提交 sitemap 地址</i>
        </label>
      </div>
      <div class="push-grid js-gsc-fields" th:attr="hidden=${!sGsc} ? 'hidden' : null">
        <div class="field">
          <label class="field-label" for="submissionGscSiteUrl" th:text="#{auto.submission.gsc.siteUrl}">站点属性</label>
          <input id="submissionGscSiteUrl" type="text" name="gscSiteUrl" autocomplete="off"
                 th:value="${sProp}" th:placeholder="#{auto.submission.gsc.siteUrl.ph}"
                 placeholder="sc-domain:example.com 或 https://example.com/">
        </div>
        <div class="field">
          <label class="field-label" for="submissionGscSitemapUrl" th:text="#{auto.submission.gsc.sitemapUrl}">sitemap 公开 URL</label>
          <input id="submissionGscSitemapUrl" type="text" name="gscSitemapUrl" autocomplete="off"
                 th:value="${sSmUri}" th:placeholder="#{auto.submission.gsc.sitemapUrl.ph}"
                 placeholder="https://example.com/sitemap.xml">
        </div>
        <div class="field field-full">
          <label class="field-label" for="submissionGscJson" th:text="#{auto.submission.gsc.json}">服务账号 JSON</label>
          <textarea id="submissionGscJson" name="gscServiceAccountJson" rows="4" autocomplete="off"
                    th:placeholder="${sHasJson} ? #{auto.submission.gsc.json.keep} : #{auto.submission.gsc.json.hint}"
                    placeholder="粘贴 Google Cloud 下载的服务账号 JSON"></textarea>
        </div>
        <div class="push-note" th:if="${sEmail != null}">
          <b th:text="#{auto.submission.gsc.clientEmail}">服务账号</b>
          <span th:text="${sEmail}">sa@proj.iam.gserviceaccount.com</span>
          <span th:text="#{auto.submission.gsc.clientEmail.hint}">需已加入 GSC 站点「用户和权限」</span>
        </div>
      </div>
      <div class="push-actions">
        <button type="submit" class="btn" th:text="#{auto.submission.save}">保存提交设置</button>
        <span class="push-note-inline" th:text="#{auto.submission.hint}">更新推送成功后自动提交；手动提交只针对最新版本。</span>
      </div>
    </form>
    <form id="submissionRunForm" method="post" th:action="@{/auto/{id}/submission/run(id=${site.id})}">
      <button type="submit" class="btn btn-ghost" th:text="#{auto.submission.run}">立即提交</button>
    </form>
    <table class="log-table" id="submissionLogTable" th:if="${!#lists.isEmpty(submissionLogs)}">
      <thead><tr>
        <th th:text="#{auto.submission.log.channel}">通道</th>
        <th th:text="#{auto.submission.log.status}">状态</th>
        <th th:text="#{auto.submission.log.detail}">详情</th>
        <th th:text="#{auto.submission.log.time}">时间</th>
      </tr></thead>
      <tbody>
        <tr th:each="log : ${submissionLogs}">
          <td th:text="${log.channel}">BAIDU</td>
          <td th:text="${log.status}">SUCCESS</td>
          <td th:text="${log.detail}"></td>
          <td th:text="${#temporals.format(log.createdAt, 'yyyy-MM-dd HH:mm')}"></td>
        </tr>
      </tbody>
    </table>
    <p class="push-note" th:if="${#lists.isEmpty(submissionLogs)}" th:text="#{auto.submission.empty}">
      还没有提交记录。
    </p>
  </div>
```

实现者注：类名/结构以本文件 push 区块实际 DOM 为准——若 `panel-head/badge/switch/push-actions/log-table/field-full/btn-ghost` 等类在 push 区块中名称不同（如 push 用的是别的包裹类），克隆 push 的同位置结构改名即可，**不新增 CSS**；两 form 是刻意的（保存与立即提交分离，参照 push 设置 form + run form 的关系，若 push 是同 form 多 action，则照 push 改同构）。checkbox 显隐 JS：把既有 `js-indexnow-key` 监听器同款复制两组（change 时 toggle `hidden` 属性）。

- [ ] **Step 3: 双语文案**

`messages.properties` 追加（zh）：

```properties
auto.submission.title=搜索引擎提交
auto.submission.on=提交已启用
auto.submission.off=未启用提交
auto.submission.baidu.enabled=启用百度提交
auto.submission.baidu.enabled.d=推送成功后提交新增与修改的 URL
auto.submission.baidu.site=百度站点
auto.submission.baidu.site.ph=https://example.com
auto.submission.baidu.token=推送 token
auto.submission.baidu.token.hint=百度搜索资源平台“普通收录”页获取
auto.submission.baidu.token.keep=已保存，留空则保持不变
auto.submission.gsc.enabled=启用 GSC 提交
auto.submission.gsc.enabled.d=推送成功后向 Google 提交 sitemap 地址
auto.submission.gsc.siteUrl=站点属性
auto.submission.gsc.siteUrl.ph=sc-domain:example.com 或 https://example.com/
auto.submission.gsc.sitemapUrl=sitemap 公开 URL
auto.submission.gsc.sitemapUrl.ph=https://example.com/sitemap.xml
auto.submission.gsc.json=服务账号 JSON
auto.submission.gsc.json.hint=粘贴 Google Cloud 下载的服务账号 JSON，并把其邮箱加入 GSC 站点用户
auto.submission.gsc.json.keep=已保存，留空则保持不变
auto.submission.gsc.clientEmail=服务账号
auto.submission.gsc.clientEmail.hint=需已加入 GSC 站点「用户和权限」
auto.submission.save=保存提交设置
auto.submission.run=立即提交
auto.submission.hint=更新推送成功后自动提交；手动提交只针对最新版本。
auto.submission.empty=还没有提交记录。
auto.submission.log.channel=通道
auto.submission.log.status=状态
auto.submission.log.detail=详情
auto.submission.log.time=时间
auto.submission.flash.saved=提交设置已保存。
auto.submission.flash.submitted=已提交搜索引擎。
```

`messages_en.properties` 追加同名 31 个 key：

```properties
auto.submission.title=Search engine submission
auto.submission.on=Submission enabled
auto.submission.off=Not submitting
auto.submission.baidu.enabled=Enable Baidu push
auto.submission.baidu.enabled.d=Submit added & changed URLs after a successful push
auto.submission.baidu.site=Baidu site
auto.submission.baidu.site.ph=https://example.com
auto.submission.baidu.token=Push token
auto.submission.baidu.token.hint=Get it from Baidu Search Resource Platform > Normal Inclusion
auto.submission.baidu.token.keep=Saved - leave blank to keep
auto.submission.gsc.enabled=Enable Search Console submission
auto.submission.gsc.enabled.d=Submit the sitemap URL to Google after a successful push
auto.submission.gsc.siteUrl=Site property
auto.submission.gsc.siteUrl.ph=sc-domain:example.com or https://example.com/
auto.submission.gsc.sitemapUrl=Public sitemap URL
auto.submission.gsc.sitemapUrl.ph=https://example.com/sitemap.xml
auto.submission.gsc.json=Service account JSON
auto.submission.gsc.json.hint=Paste the JSON downloaded from Google Cloud and add its email to GSC site users
auto.submission.gsc.json.keep=Saved - leave blank to keep
auto.submission.gsc.clientEmail=Service account
auto.submission.gsc.clientEmail.hint=Must be added under GSC "Users and permissions"
auto.submission.save=Save submission settings
auto.submission.run=Submit now
auto.submission.hint=Submits automatically after push; manual submit targets the latest version.
auto.submission.empty=No submissions yet.
auto.submission.log.channel=Channel
auto.submission.log.status=Status
auto.submission.log.detail=Detail
auto.submission.log.time=Time
auto.submission.flash.saved=Submission settings saved.
auto.submission.flash.submitted=Submitted to search engines.
```

- [ ] **Step 4: 运行确认通过（含 i18n 门禁）**

`mvn -o test -Dtest=AutoSiteControllerTest+MessagesAlignmentTest` → PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/auto-detail.html src/main/resources/messages.properties \
        src/main/resources/messages_en.properties \
        src/test/java/io/github/ghgongjin/sitemap/controller/AutoSiteControllerTest.java
git commit -m "feat: 自动更新详情页搜索引擎提交区块与双语文案"
```

---

### Task 9: 全量验证 + jar 冒烟 + 走查取证

**Files:**
- 无新文件（验证任务）；若 README 有功能清单提及 IndexNow，则同步补一行百度/GSC（Modify: `README.md`）。

**Interfaces:**
- Consumes: Task 1-8 全部产物。
- Produces: 绿色全量测试证据、冒烟证据、走查环境说明。

- [ ] **Step 1: 全量回归**

Run: `mvn -o clean verify 2>&1 | iconv -f GBK -t UTF-8 | tail -30`
Expected: BUILD SUCCESS；总数 ≥ 539+约 40 新增，0 失败，7 跳过不变。任何失败：修复后重跑，禁止跳过。

- [ ] **Step 2: jar 冒烟（真实启动 + 旧库升级）**

```bash
cp data/sitemapdb.mv.db /tmp/pre-c-smoke-db.bak    # 备份在用库（若有）
mvn -o package -DskipTests -q
java -jar target/sitemap-studio-*.jar --server.port=8097 > /tmp/smoke-c.log 2>&1 &
sleep 12
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8097/login
curl -s http://localhost:8097/auto | head -c 200   # 302 到 /login 也属正常
grep -i "NULL not allowed\|CommandAcceptanceException" /tmp/smoke-c.log   # 必须无输出（旧库加列防线）
```

Expected: 首页 200/302 正常；启动日志零 DDL 错误。验证后杀掉该进程并恢复数据目录（如有改动）。

- [ ] **Step 3: 登录态走查（curl 或浏览器）**

用 harness/临时注册账号登录 8097，打开 `/auto/{id}` 详情页：确认「搜索引擎提交」区块渲染（双语切换后文案完整）、保存非法百度站点（带路径）得到红字提示、提交历史空态文案。百度通道用真实 token 端到端由用户验证（无凭据时不伪造成功）。

- [ ] **Step 4: README 功能行（条件）**

`grep -n "IndexNow" README.md`；若功能清单命中，在对应条目处追加"百度主动推送 / Google Search Console 提交"（中英各一处，风格对齐）。无清单命中则跳过。

- [ ] **Step 5: Commit + 推分支 + 报告**

```bash
git add -A && git commit -m "docs: README 功能清单补充搜索引擎提交通道"   # 仅 Step 4 有改动时
git status --short   # 必须干净
```

推送分支、汇报全量测试计数与冒烟证据，等待合并指示（合并不自行动作）。

---

## 发布后（不在本计划内，提醒项）

- 版本号提升 / tag / Release 属发布决策，先问用户；
- 真实百度 token 端到端提交需用户凭据，走查时邀请用户当场验证。
