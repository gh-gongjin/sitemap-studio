# 变更 diff 与告警（功能 A）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 自动更新站点在版本间展示 URL 集合差异（+新增/−删除/~改动），并按 Webhook + 邮件双通道对「URL 变化 / 连续失败 1·3·10 / SEO 错误跨阈值」三类事件主动告警。

**Architecture:** 写入时计算：`AutoSiteService.recordSuccess` 用纯函数 `SiteDiffEngine` 对比相邻版本 XML、把三个计数冗余存进版本行；`AutoSiteUpdater` 在成功/失败后发布 Spring 事件；`NotificationService`（@EventListener + @Async 独立小线程池）做触发判定与载荷组装，分发给 `WebhookSender` / `MailSender` 两个通道，任何通知异常被吞掉记 WARN，与爬取主流程完全隔离。明细不落库、按需实时算。

**Tech Stack:** Spring Boot 3.5.16 / Java 21 / Spring Data JPA (H2) / Thymeleaf / Lombok / Mockito+AssertJ / spring-boot-starter-mail（新增，仅 `spring.mail.host` 存在时装配 MailSender）

**Spec:** `docs/superpowers/specs/2026-09-19-diff-notification-design.md`（本计划的约束与语义以该文件为准，两者必须一起读）

## Global Constraints

- 包根：`io.github.ghgongjin.sitemap`；实体表列名 snake_case；`spring.jpa.hibernate.ddl-auto=update`（生产库新列自动加，无迁移脚本）
- 构建/测试离线执行：`mvn -o test`（Task 1 有一次联网拉取依赖例外）；单测命令模板 `mvn -o test -Dtest=XxxTest`
- **新测试不得依赖宿主机 locale**（CI 约定）：断言渲染文案时应用默认 zh（`CookieLocaleResolver` 默认 SIMPLIFIED_CHINESE 且 `fallback-to-system-locale=false`）或显式 `?lang=`，禁止 `Locale.getDefault()`
- `CrawlUrlPolicy` **零改动**（SSRF 红线）；webhook 策略放独立新类 `WebhookUrlPolicy`；云元数据/链路本地段无条件拒绝，私有网段由 `sitemap.notify.allow-private-network`（默认 true）控制
- 通知侧任何异常绝不冒泡到爬取/存版本/推送主流程；监听器一律异步（`notifyExecutor`：2 线程/队列 100/满则丢最旧）
- 归属校验失败一律 404（`ResponseStatusException(HttpStatus.NOT_FOUND)`）；`SecurityConfig` 不动（`/auto/**` 已要求登录）
- 全部新文案：`messages.properties`（zh 基线）与 `messages_en.properties` 成对添加，两文件各 348 行现状，UTF-8 编码
- 前端禁浏览器原生交互：表单 `novalidate` + 既有 `SitemapUI.formGuard`/`SitemapUI.confirm`；新页面沿用 `fragments/layout` 与 app.css 类名
- 中文注释风格与既有文件一致（类头 `@ClassName/@Description/@Author gj/@Date/@Version`）；commit message 用中文 `feat:`/`test:`/`docs:` 前缀；git 身份用仓库级配置，勿动全局
- application.properties 历史注释有双重编码乱码，**只允许追加 ASCII 注释**
- 每任务结束：新测试全绿 + 受影响既有测试修复后全绿，然后 `git commit`

## 文件结构（全量）

**新建（main）**
| 文件 | 职责 |
|---|---|
| `config/NotifyProperties.java` | `sitemap.notify.*` 配置绑定 |
| `config/NotificationConfig.java` | `@EnableAsync` + `notifyExecutor` 线程池 + `WebhookUrlPolicy` Bean |
| `service/SiteDiffEngine.java` | 纯函数：两版 XML → `SiteDiff(added, removed, changed)` |
| `service/Csv.java` | 自 `SeoReportService.csvRow` 提取的 CSV 转义/公式注入防护 |
| `service/notify/SiteUpdatedEvent.java` `SiteFailedEvent.java` | 不可变事件 record |
| `service/notify/NotificationType.java` `NotificationPayload.java` | 载荷（与通道无关） |
| `service/notify/NotifyChannel.java` `NotifyOutcome.java` | 通道契约 |
| `service/notify/WebhookUrlPolicy.java` | webhook URL 安全策略（返回枚举，不抛业务文案） |
| `service/notify/WebhookSender.java` | POST JSON + HMAC 签名 + 超时 + 1 次重试 |
| `service/notify/MailSender.java` | 条件装配的邮件通道（text+html） |
| `service/notify/NotificationService.java` | 唯一监听器：触发矩阵、配额、分发、`test(siteId)` |
| `service/notify/NotifySettings.java` `NotifySettingsView.java` `NotifySettingsService.java` | 通知设置表单/视图/落库（secret 加密留空即沿用） |
| `service/notify/NotifyTestLimiter.java` | 测试通知内存令牌桶（每站 3 次/分钟） |
| `templates/auto-diff.html` | 版本详情页：三组明细截断 100 + CSV 链接 |
| `templates/mail/notify-changed.html` `notify-failed.html` `notify-test.html` | 邮件 HTML 模板（`${}` 变量 + 固定双语，不用 `#{}`） |

**修改（main）**：`entity/AutoSite.java`、`entity/AutoSiteVersion.java`、`service/AutoSiteService.java`（recordSuccess 算 diff / recordFailure 计数）、`service/AutoSiteUpdater.java`（发布事件）、`service/SeoReportService.java`（csvRow 委托 Csv）、`controller/AutoSiteController.java`（4 新端点 + detail 加 notify 模型）、`templates/auto-detail.html`（徽标列 + 通知面板）、`pom.xml`、`application.properties`、messages zh/en 两份。

**测试**：`SiteDiffEngineTest`、`WebhookUrlPolicyTest`、`WebhookSenderTest`、`MailSenderTest`、`NotificationServiceTest`、`NotifySettingsServiceTest`、`AutoSiteDiffBadgeTest`（MockMvc 读侧）、`NotifySettingsControllerTest`（MockMvc 写侧）、`MessagesAlignmentTest`、以及 `AutoSiteServiceTest`/`AutoSiteUpdaterTest`/`AutoSiteRepositoryTest` 追加用例。

---

### Task 1: 依赖与通知配置底座

**Files:**
- Modify: `pom.xml`（dependencies 段，`spring-boot-starter-websocket` 块之后插入）
- Modify: `src/main/resources/application.properties`（文件末尾追加）
- Create: `src/main/java/io/github/ghgongjin/sitemap/config/NotifyProperties.java`
- Test: `src/test/java/io/github/ghgongjin/sitemap/config/NotifyPropertiesBindingTest.java`

**Interfaces:**
- Produces: `NotifyProperties`（`@Data`）：`boolean enabled=true`、`int webhookTimeoutMs=10000`、`boolean allowPrivateNetwork=true`、`int maxEmailsPerSitePerDay=50`；getter：`isEnabled()`、`getWebhookTimeoutMs()`、`isAllowPrivateNetwork()`、`getMaxEmailsPerSitePerDay()`。后续任务全部注入该类。

- [ ] **Step 1: pom.xml 添加 mail starter**

在 `spring-boot-starter-websocket` 依赖块后加：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-mail</artifactId>
        </dependency>
```

- [ ] **Step 2: 一次性联网拉取（本机 .m2 无该构件，之后恢复离线）**

Run: `mvn -q dependency:resolve` （联网；github/maven 偶发超时则重试至多 5 次）
Verify: `ls ~/.m2/repository/org/springframework/boot/spring-boot-starter-mail/3.5.16` 存在 jar/pom。
之后一律 `mvn -o`。

- [ ] **Step 3: application.properties 追加配置（仅 ASCII 注释）**

```properties

# Diff & notification (feature A). spring.mail.* intentionally unset: without a host the
# MailSender bean is not created and notifications are webhook-only.
sitemap.notify.enabled=true
sitemap.notify.webhook-timeout-ms=10000
sitemap.notify.allow-private-network=true
sitemap.notify.max-emails-per-site-per-day=50
```

- [ ] **Step 4: 写失败测试**

`NotifyPropertiesBindingTest.java`：

```java
package io.github.ghgongjin.sitemap.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName NotifyPropertiesBindingTest
 * @Description 通知配置绑定：application.properties 提供全部键，默认值与 spec 一致
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
@SpringBootTest
@ActiveProfiles("test")
class NotifyPropertiesBindingTest {

    @Autowired
    private NotifyProperties props;

    @Test
    void shouldBindDefaultsFromApplicationProperties() {
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getWebhookTimeoutMs()).isEqualTo(10000);
        assertThat(props.isAllowPrivateNetwork()).isTrue();
        assertThat(props.getMaxEmailsPerSitePerDay()).isEqualTo(50);
    }
}
```

- [ ] **Step 5: 跑红**

Run: `mvn -o test -Dtest=NotifyPropertiesBindingTest`
Expected: 编译失败（`NotifyProperties` 不存在）——Java TDD 的合法红。

- [ ] **Step 6: 实现 NotifyProperties**

```java
package io.github.ghgongjin.sitemap.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @ClassName NotifyProperties
 * @Description 变更告警全局配置（sitemap.notify.*）：一键关停、webhook 超时、内网放行、邮件日配额
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "sitemap.notify")
public class NotifyProperties {

    private boolean enabled = true;
    private int webhookTimeoutMs = 10000;
    private boolean allowPrivateNetwork = true;
    private int maxEmailsPerSitePerDay = 50;
}
```

- [ ] **Step 7: 跑绿**

Run: `mvn -o test -Dtest=NotifyPropertiesBindingTest` → PASS；再 `mvn -o test -DargLine="-Duser.language=en -Duser.country=US"` 抽跑本类确认不依赖宿主机语言。

- [ ] **Step 8: Commit**

```bash
git add pom.xml src/main/resources/application.properties src/main/java/io/github/ghgongjin/sitemap/config/NotifyProperties.java src/test/java/io/github/ghgongjin/sitemap/config/NotifyPropertiesBindingTest.java
git commit -m "build: 通知功能配置底座（starter-mail 依赖 + sitemap.notify.* 配置项）"
```

---

### Task 2: 实体列扩展（版本 diff 计数 + 站点通知设置）

**Files:**
- Modify: `src/main/java/io/github/ghgongjin/sitemap/entity/AutoSiteVersion.java`
- Modify: `src/main/java/io/github/ghgongjin/sitemap/entity/AutoSite.java`
- Test: `src/test/java/io/github/ghgongjin/sitemap/repository/AutoSiteRepositoryTest.java`（追加两个用例）

**Interfaces:**
- Produces: `AutoSiteVersion.getDiffAdded()/getDiffRemoved()/getDiffChanged()`、对应 `setDiff…(int)`；`AutoSite` 新字段 `Integer consecutiveFailures`、`String notifyWebhookUrl`、`String notifyWebhookSecretEnc`、`String notifyEmail`、`Boolean notifyOnChange`、`Boolean notifyOnFailure`、`Integer notifySeoErrorThreshold`，及有效值访问器 `isNotifyOnChangeEffective()`（null=true）、`isNotifyOnFailureEffective()`（null=true）、`consecutiveFailuresOrZero()`、`notifySeoErrorThresholdOrOff()`（null→-1）、`hasNotifyChannelConfigured()`。包装类型是对存量行（升级后新列为 NULL）的读保护，禁止改回原始类型。

- [ ] **Step 1: 写失败测试**（追加到 `AutoSiteRepositoryTest` 类体内，import 无需新增）

```java
    @Test
    void shouldPersistDiffCountsOnVersion() {
        // Given
        AutoSiteVersion version = new AutoSiteVersion();
        version.setSiteId(99L);
        version.setVersionNumber(2);
        version.setTaskId("task-diff");
        version.setUrlCount(3);
        version.setSitemapXml("<urlset></urlset>");
        version.setCreatedAt(LocalDateTime.now());
        version.setDiffAdded(7);
        version.setDiffRemoved(2);
        version.setDiffChanged(1);

        // When
        AutoSiteVersion saved = versionRepository.saveAndFlush(version);

        // Then
        AutoSiteVersion found = versionRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getDiffAdded()).isEqualTo(7);
        assertThat(found.getDiffRemoved()).isEqualTo(2);
        assertThat(found.getDiffChanged()).isEqualTo(1);
    }

    @Test
    void shouldTreatNullNotifyColumnsAsDefaultsWhenLegacyRow() {
        // Given：存量站点升级后通知列为 NULL（不写入即 null）
        AutoSite site = site("https://legacy.example.com", true, LocalDateTime.now(), true);
        site.setNotifyWebhookUrl("https://hooks.example.com/x");

        // When
        AutoSite saved = siteRepository.saveAndFlush(site);

        // Then：包装列可读为 null，有效值访问器给出 spec 默认（开/0/-1）
        AutoSite found = siteRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getConsecutiveFailures()).isNull();
        assertThat(found.getNotifyOnChange()).isNull();
        assertThat(found.isNotifyOnChangeEffective()).isTrue();
        assertThat(found.isNotifyOnFailureEffective()).isTrue();
        assertThat(found.consecutiveFailuresOrZero()).isZero();
        assertThat(found.notifySeoErrorThresholdOrOff()).isEqualTo(-1);
        assertThat(found.hasNotifyChannelConfigured()).isTrue();
    }
```

（`site(...)` 是该测试类既有工厂方法，签名 `site(String url, boolean enabled, LocalDateTime nextRunAt, boolean includeNews)` 以类内实际为准；若参数序不同按现状调用。）

- [ ] **Step 2: 跑红**

Run: `mvn -o test -Dtest=AutoSiteRepositoryTest` → 编译失败（setter 不存在）。

- [ ] **Step 3: 实现实体字段**

`AutoSiteVersion.java` 在 `sitemapXml` 字段后追加：

```java
    @Column(name = "diff_added", nullable = false)
    private int diffAdded;

    @Column(name = "diff_removed", nullable = false)
    private int diffRemoved;

    @Column(name = "diff_changed", nullable = false)
    private int diffChanged;
```

`AutoSite.java` 在 `lastMessage` 字段后追加（含访问器，放在类尾）：

```java
    /** 连续爬取失败次数；成功即清零。包装类型：存量行升级后该列为 NULL */
    @Column(name = "consecutive_failures")
    private Integer consecutiveFailures;

    @Column(name = "notify_webhook_url", length = 2048)
    private String notifyWebhookUrl;

    @Column(name = "notify_webhook_secret_enc", length = 1024)
    private String notifyWebhookSecretEnc;

    @Column(name = "notify_email", length = 256)
    private String notifyEmail;

    @Column(name = "notify_on_change")
    private Boolean notifyOnChange;

    @Column(name = "notify_on_failure")
    private Boolean notifyOnFailure;

    @Column(name = "notify_seo_error_threshold")
    private Integer notifySeoErrorThreshold;
```

```java
    public boolean isNotifyOnChangeEffective() {
        return notifyOnChange == null || notifyOnChange;
    }

    public boolean isNotifyOnFailureEffective() {
        return notifyOnFailure == null || notifyOnFailure;
    }

    public int consecutiveFailuresOrZero() {
        return consecutiveFailures == null ? 0 : consecutiveFailures;
    }

    public int notifySeoErrorThresholdOrOff() {
        return notifySeoErrorThreshold == null ? -1 : notifySeoErrorThreshold;
    }

    public boolean hasNotifyChannelConfigured() {
        return (notifyWebhookUrl != null && !notifyWebhookUrl.isBlank())
                || (notifyEmail != null && !notifyEmail.isBlank());
    }
```

- [ ] **Step 4: 跑绿**

Run: `mvn -o test -Dtest=AutoSiteRepositoryTest` → PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/ghgongjin/sitemap/entity src/test/java/io/github/ghgongjin/sitemap/repository/AutoSiteRepositoryTest.java
git commit -m "feat: 站点/版本实体扩展 diff 计数与通知设置列（存量 NULL 安全）"
```

---

### Task 3: SiteDiffEngine 纯函数

**Files:**
- Create: `src/main/java/io/github/ghgongjin/sitemap/service/SiteDiffEngine.java`
- Test: `src/test/java/io/github/ghgongjin/sitemap/service/SiteDiffEngineTest.java`

**Interfaces:**
- Consumes: `SitemapEntryParser.parse(String)` → `SitemapEntries.entries()`：`Entry(String url, String lastmod, String priority)`（url 空白项 parse 已剔除；lastmod 缺失为 `""`）。
- Produces: `SiteDiffEngine.diff(String xmlOld, String xmlNew)` → `SiteDiff`（`record SiteDiff(List<String> added, List<String> removed, List<String> changed)`，均按字典序排序，含 `int total()`）。`xmlOld == null || xmlNew == null` 抛 `IllegalArgumentException("sitemap xml missing")`（历史 XML 缺失=「无法比较」信号，Task 4/10 依赖此异常）。

- [ ] **Step 1: 写失败测试**

```java
package io.github.ghgongjin.sitemap.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @ClassName SiteDiffEngineTest
 * @Description 版本间 URL 集合差异纯函数：增删改语义、重复 loc、空/缺 XML 边界、千级性能冒烟
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
class SiteDiffEngineTest {

    private static String urlset(String... urlAndLastmodPairs) {
        StringBuilder sb = new StringBuilder("<urlset>");
        for (int i = 0; i < urlAndLastmodPairs.length; i += 2) {
            sb.append("<url><loc>").append(urlAndLastmodPairs[i])
                    .append("</loc><lastmod>").append(urlAndLastmodPairs[i + 1])
                    .append("</lastmod></url>");
        }
        return sb.append("</urlset>").toString();
    }

    static Stream<Arguments> diffMatrix() {
        return Stream.of(
                // 名称 / 旧版 / 新版 / added / removed / changed
                Arguments.of("addedOnly",
                        urlset("https://a/x", "2026-01-01"),
                        urlset("https://a/x", "2026-01-01", "https://a/y", "2026-01-02"),
                        List.of("https://a/y"), List.of(), List.of()),
                Arguments.of("removedOnly",
                        urlset("https://a/x", "2026-01-01", "https://a/y", "2026-01-01"),
                        urlset("https://a/x", "2026-01-01"),
                        List.of(), List.of("https://a/y"), List.of()),
                Arguments.of("changedByLastmod",
                        urlset("https://a/x", "2026-01-01"),
                        urlset("https://a/x", "2026-02-02"),
                        List.of(), List.of(), List.of("https://a/x")),
                Arguments.of("identicalYieldsEmpty",
                        urlset("https://a/x", "2026-01-01"),
                        urlset("https://a/x", "2026-01-01"),
                        List.of(), List.of(), List.of()),
                Arguments.of("missingLastmodTreatedAsEmptyString",
                        "<urlset><url><loc>https://a/x</loc></url></urlset>",
                        urlset("https://a/x", "2026-01-01"),
                        List.of(), List.of(), List.of("https://a/x")),
                Arguments.of("locIsCaseSensitive",
                        urlset("https://a/X", "2026-01-01"),
                        urlset("https://a/x", "2026-01-01"),
                        List.of("https://a/x"), List.of("https://a/X"), List.of()),
                Arguments.of("emptyXmlMeansAllRemoved",
                        urlset("https://a/x", "2026-01-01"),
                        "<urlset></urlset>",
                        List.of(), List.of("https://a/x"), List.of()),
                Arguments.of("sortedOutputs",
                        urlset("https://a/z", "2026-01-01"),
                        urlset("https://a/z", "2026-01-01", "https://a/b", "2026-01-01", "https://a/m", "2026-01-01"),
                        List.of("https://a/b", "https://a/m"), List.of(), List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("diffMatrix")
    void shouldComputeDiff(String name, String oldXml, String newXml,
                           List<String> added, List<String> removed, List<String> changed) {
        SiteDiffEngine.SiteDiff diff = SiteDiffEngine.diff(oldXml, newXml);
        assertThat(diff.added()).as(name + ".added").containsExactlyElementsOf(added);
        assertThat(diff.removed()).as(name + ".removed").containsExactlyElementsOf(removed);
        assertThat(diff.changed()).as(name + ".changed").containsExactlyElementsOf(changed);
    }

    @Test
    void shouldLetDuplicateLocKeepLastLastmod() {
        String oldXml = urlset("https://a/x", "2026-01-01", "https://a/x", "2026-03-03");
        String newXml = urlset("https://a/x", "2026-03-03");
        assertThat(SiteDiffEngine.diff(oldXml, newXml).changed()).isEmpty();
    }

    @Test
    void shouldThrowWhenEitherXmlMissing() {
        assertThatThrownBy(() -> SiteDiffEngine.diff(null, urlset("https://a/x", "2026-01-01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SiteDiffEngine.diff(urlset("https://a/x", "2026-01-01"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldDiffOneThousandUrlsWithinOneSecond() {
        List<String> oldPairs = new ArrayList<>();
        List<String> newPairs = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            oldPairs.add("https://a/p" + i);
            oldPairs.add("2026-01-01");
            newPairs.add("https://a/p" + i);
            newPairs.add(i % 2 == 0 ? "2026-01-01" : "2026-02-02");
        }
        newPairs.add("https://a/new");
        newPairs.add("2026-02-02");
        long start = System.nanoTime();
        SiteDiffEngine.SiteDiff diff = SiteDiffEngine.diff(urlset(oldPairs.toArray(String[]::new)),
                urlset(newPairs.toArray(String[]::new)));
        assertThat(System.nanoTime() - start).isLessThan(1_000_000_000L);
        assertThat(diff.changed()).hasSize(500);
        assertThat(diff.added()).containsExactly("https://a/new");
        assertThat(diff.total()).isEqualTo(501);
    }
}
```

- [ ] **Step 2: 跑红**

Run: `mvn -o test -Dtest=SiteDiffEngineTest` → 编译失败。

- [ ] **Step 3: 实现**

```java
package io.github.ghgongjin.sitemap.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @ClassName SiteDiffEngine
 * @Description 站点地图相邻版本 diff 纯函数：added/removed 按 loc 精确匹配，changed 为同 loc 且 lastmod 字符串不同
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
public final class SiteDiffEngine {

    /**
     * @param xmlOld 上一版完整 XML
     * @param xmlNew 本版完整 XML
     * @throws IllegalArgumentException 任一入参为 null（历史数据缺失，按「无法比较」处理）
     */
    public static SiteDiff diff(String xmlOld, String xmlNew) {
        if (xmlOld == null || xmlNew == null) {
            throw new IllegalArgumentException("sitemap xml missing");
        }
        Map<String, String> oldEntries = lastmodByLoc(xmlOld);
        Map<String, String> newEntries = lastmodByLoc(xmlNew);
        List<String> added = newEntries.keySet().stream()
                .filter(loc -> !oldEntries.containsKey(loc))
                .sorted().toList();
        List<String> removed = oldEntries.keySet().stream()
                .filter(loc -> !newEntries.containsKey(loc))
                .sorted().toList();
        List<String> changed = newEntries.entrySet().stream()
                .filter(e -> oldEntries.containsKey(e.getKey())
                        && !e.getValue().equals(oldEntries.get(e.getKey())))
                .map(Map.Entry::getKey)
                .sorted().toList();
        return new SiteDiff(added, removed, changed);
    }

    private static Map<String, String> lastmodByLoc(String xml) {
        Map<String, String> map = new LinkedHashMap<>();
        for (SitemapEntryParser.Entry entry : SitemapEntryParser.parse(xml).entries()) {
            // 重复 loc：后出现者覆盖（生成器不产重复，仅防御）
            map.put(entry.url(), entry.lastmod());
        }
        return map;
    }

    public record SiteDiff(List<String> added, List<String> removed, List<String> changed) {

        public int total() {
            return added.size() + removed.size() + changed.size();
        }
    }

    private SiteDiffEngine() {
    }
}
```

- [ ] **Step 4: 跑绿**

Run: `mvn -o test -Dtest=SiteDiffEngineTest` → 全 PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/ghgongjin/sitemap/service/SiteDiffEngine.java src/test/java/io/github/ghgongjin/sitemap/service/SiteDiffEngineTest.java
git commit -m "feat: SiteDiffEngine 相邻版本 URL 集合差异纯函数（增/删/改，表驱动测试）"
```

---

### Task 4: recordSuccess 写入 diff 计数 + 连续失败计数

**Files:**
- Modify: `src/main/java/io/github/ghgongjin/sitemap/service/AutoSiteService.java:176-215`（recordSuccess / recordFailure）
- Test: `src/test/java/io/github/ghgongjin/sitemap/service/AutoSiteServiceTest.java`（追加 + 修一处既有用例）

**Interfaces:**
- Consumes: `SiteDiffEngine.diff`（Task 3）。
- Produces: `recordSuccess` 返回类型 **不变**（`AutoSite`），但新落库的 `AutoSiteVersion` 带 0..n 计数；首版（无上一版）计数恒为 0/0/0；diff 计算抛异常时 0/0/0 + WARN（版本照常保存）；成功路径 `site.setConsecutiveFailures(0)`；`recordFailure` 使 `site.setConsecutiveFailures(consecutiveFailuresOrZero() + 1)`，返回值仍是 `AutoSite`（携带新计数）。

- [ ] **Step 1: 写失败测试**（追加到 `AutoSiteServiceTest`）

```java
    @Test
    void shouldStoreDiffCountsOnSecondVersionWhenSuccess() {
        // Given
        AutoSite site = site(1L, 24);
        AutoSiteVersion previous = new AutoSiteVersion();
        previous.setVersionNumber(1);
        previous.setSitemapXml("<urlset><url><loc>https://a/x</loc><lastmod>2026-01-01</lastmod>"
                + "<url><loc>https://a/gone</loc><lastmod>2026-01-01</lastmod></url></urlset>");
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(versionRepository.findTopBySiteIdOrderByVersionNumberDesc(1L)).thenReturn(Optional.of(previous));
        when(versionRepository.findBySiteIdOrderByVersionNumberDesc(1L)).thenReturn(List.of(previous));

        // When
        service.recordSuccess(1L, "task-2",
                "<urlset><url><loc>https://a/x</loc><lastmod>2026-02-02</lastmod>"
                        + "<url><loc>https://a/new</loc><lastmod>2026-02-02</lastmod></url></urlset>", 2);

        // Then：x 改、new 增、gone 删
        ArgumentCaptor<AutoSiteVersion> captor = ArgumentCaptor.forClass(AutoSiteVersion.class);
        verify(versionRepository).save(captor.capture());
        assertThat(captor.getValue().getDiffAdded()).isEqualTo(1);
        assertThat(captor.getValue().getDiffRemoved()).isEqualTo(1);
        assertThat(captor.getValue().getDiffChanged()).isEqualTo(1);
    }

    @Test
    void shouldZeroCountsAndStillSaveVersionWhenDiffFailsOnCorruptedPrevious() {
        // Given：上一版 sitemapXml 为 null（模拟损坏/缺失历史）
        AutoSite site = site(1L, 24);
        AutoSiteVersion previous = new AutoSiteVersion();
        previous.setVersionNumber(1);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(versionRepository.findTopBySiteIdOrderByVersionNumberDesc(1L)).thenReturn(Optional.of(previous));
        when(versionRepository.findBySiteIdOrderByVersionNumberDesc(1L)).thenReturn(List.of(previous));

        // When
        AutoSite updated = service.recordSuccess(1L, "task-2", XML, 5);

        // Then：版本照常保存，计数 0/0/0
        ArgumentCaptor<AutoSiteVersion> captor = ArgumentCaptor.forClass(AutoSiteVersion.class);
        verify(versionRepository).save(captor.capture());
        assertThat(captor.getValue().getDiffAdded()).isZero();
        assertThat(captor.getValue().getDiffRemoved()).isZero();
        assertThat(captor.getValue().getDiffChanged()).isZero();
        assertThat(updated.getLastStatus()).isEqualTo(AutoSiteService.STATUS_SUCCESS);
    }

    @Test
    void shouldResetConsecutiveFailuresOnSuccess() {
        AutoSite site = site(1L, 24);
        site.setConsecutiveFailures(4);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(versionRepository.findTopBySiteIdOrderByVersionNumberDesc(1L)).thenReturn(Optional.empty());
        when(versionRepository.findBySiteIdOrderByVersionNumberDesc(1L)).thenReturn(List.of());

        AutoSite updated = service.recordSuccess(1L, "task-1", XML, 1);

        assertThat(updated.getConsecutiveFailures()).isZero();
    }

    @Test
    void shouldIncrementConsecutiveFailuresOnFailure() {
        AutoSite site = site(1L, 24);
        site.setConsecutiveFailures(2);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        AutoSite updated = service.recordFailure(1L, "连接超时");

        assertThat(updated.getConsecutiveFailures()).isEqualTo(3);
    }
```

既有 `shouldWriteFirstVersionWhenSuccess`（约 339 行）无需改（首版路径不触 diff）；若其断言受 `consecutiveFailures=0` 写入影响，仅在该用例补 `assertThat(updated.getConsecutiveFailures()).isZero();` 不改行为。

- [ ] **Step 2: 跑红**

Run: `mvn -o test -Dtest=AutoSiteServiceTest` → 新用例失败（计数恒 0 / 不递增）。

- [ ] **Step 3: 实现**

`AutoSiteService.recordSuccess` 改为（在既有方法上编辑，保留原有日志/裁剪）：

```java
    @Transactional
    public AutoSite recordSuccess(Long id, String taskId, String sitemapXml, int urlCount) {
        AutoSite site = requireSite(id);
        LocalDateTime now = LocalDateTime.now();

        AutoSiteVersion previous = versionRepository
                .findTopBySiteIdOrderByVersionNumberDesc(site.getId()).orElse(null);
        AutoSiteVersion version = new AutoSiteVersion();
        version.setSiteId(site.getId());
        version.setVersionNumber(previous == null ? 1 : previous.getVersionNumber() + 1);
        version.setTaskId(taskId);
        version.setUrlCount(urlCount);
        version.setSitemapXml(sitemapXml);
        version.setCreatedAt(now);
        applyDiffCounts(version, previous, sitemapXml, id);
        versionRepository.save(version);
        trimVersions(site.getId());

        site.setLastRunAt(now);
        site.setLastStatus(STATUS_SUCCESS);
        site.setLastMessage(null);
        site.setConsecutiveFailures(0);
        site.setNextRunAt(now.plusHours(site.getIntervalHours()));
        site.setUpdatedAt(now);
        AutoSite saved = siteRepository.save(site);
        log.info("自动更新成功：{}，版本 {}，{} 个 URL", site.getUrl(), version.getVersionNumber(), urlCount);
        return saved;
    }

    /**
     * 首版恒 0/0/0；diff 计算异常（历史 XML 缺失/损坏）时保持 0/0/0 并 WARN，版本照常保存
     */
    private void applyDiffCounts(AutoSiteVersion version, AutoSiteVersion previous, String sitemapXml, Long siteId) {
        if (previous == null) {
            return;
        }
        try {
            SiteDiffEngine.SiteDiff diff = SiteDiffEngine.diff(previous.getSitemapXml(), sitemapXml);
            version.setDiffAdded(diff.added().size());
            version.setDiffRemoved(diff.removed().size());
            version.setDiffChanged(diff.changed().size());
        } catch (Exception e) {
            log.warn("版本 diff 计算失败（siteId={}）：{}", siteId, e.getMessage());
        }
    }
```

注意：原实现里版本号来自私有方法 `nextVersionNumber(site.getId())`，其语义（top+1 或 1）与上面内联等价；**保留** `nextVersionNumber` 若仍被引用，否则删除（编译器会报未使用——本类私有且仅 recordSuccess 用，删）。
`recordFailure` 在 `site.setLastMessage(...)` 之后加一行：

```java
        site.setConsecutiveFailures(site.consecutiveFailuresOrZero() + 1);
```

- [ ] **Step 4: 跑绿 + 类内回归**

Run: `mvn -o test -Dtest=AutoSiteServiceTest` → 全 PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/ghgongjin/sitemap/service/AutoSiteService.java src/test/java/io/github/ghgongjin/sitemap/service/AutoSiteServiceTest.java
git commit -m "feat: 版本保存时写入相邻 diff 计数，成功清零/失败递增连续计数"
```

---

### Task 5: 事件定义与 AutoSiteUpdater 发布接线

**Files:**
- Create: `src/main/java/io/github/ghgongjin/sitemap/service/notify/SiteUpdatedEvent.java`、`SiteFailedEvent.java`
- Modify: `src/main/java/io/github/ghgongjin/sitemap/service/AutoSiteUpdater.java`
- Test: `src/test/java/io/github/ghgongjin/sitemap/service/AutoSiteUpdaterTest.java`（setUp 改造 + 追加用例）

**Interfaces:**
- Consumes: `AutoSiteService.recordSuccess/recordFailure/latestVersion`（Task 4 语义）。
- Produces:

```java
public record SiteUpdatedEvent(Long siteId, Long versionId, int versionNumber,
        int diffAdded, int diffRemoved, int diffChanged,
        Integer seoErrorCount /* 无 SEO 报告为 null */, boolean firstVersion) {}

public record SiteFailedEvent(Long siteId, int consecutiveFailures, String message) {}
```

`AutoSiteUpdater` 构造参数追加末位 `ApplicationEventPublisher events`（@RequiredArgsConstructor 字段顺序：autoSiteService, enhancedSitemapGeneratorService, progressService, seoReportService, sitemapPushService, **events**）。成功事件在 `recordSuccess`+`saveSeoReport` 之后、推送之前发布；事件发布自身 try/catch 吞异常。

- [ ] **Step 1: 写失败测试**

先改 `AutoSiteUpdaterTest.setUp()`（构造与桩）：

```java
    private ApplicationEventPublisher events;

    @BeforeEach
    void setUp() {
        autoSiteService = mock(AutoSiteService.class);
        enhancedService = mock(EnhancedSitemapGeneratorService.class);
        progressService = mock(CrawlProgressService.class);
        seoReportService = mock(SeoReportService.class);
        pushService = mock(SitemapPushService.class);
        events = mock(ApplicationEventPublisher.class);
        updater = new AutoSiteUpdater(autoSiteService, enhancedService, progressService,
                seoReportService, pushService, events);
        // recordSuccess 返回后 updater 会重读最新版本与失败站点，给默认桩
        when(autoSiteService.latestVersion(anyLong())).thenReturn(Optional.of(successVersion(1)));
        when(autoSiteService.recordFailure(anyLong(), any()))
                .thenAnswer(inv -> failedSite(1));
    }

    private static io.github.ghgongjin.sitemap.entity.AutoSiteVersion successVersion(int vno) {
        io.github.ghgongjin.sitemap.entity.AutoSiteVersion version = new io.github.ghgongjin.sitemap.entity.AutoSiteVersion();
        version.setId((long) vno);
        version.setSiteId(1L);
        version.setVersionNumber(vno);
        version.setTaskId("task-" + vno);
        return version;
    }

    private static AutoSite failedSite(int failures) {
        AutoSite s = site(1L);
        s.setConsecutiveFailures(failures);
        s.setLastMessage("站点地图生成失败: 连接超时");
        return s;
    }
```

（`site(1L)`、`result(int)` 为该测试类既有工厂。新增 import：`org.springframework.context.ApplicationEventPublisher`、`java.util.Optional`、`io.github.ghgongjin.sitemap.service.notify.SiteUpdatedEvent`、`SiteFailedEvent`。既有失败用例 `shouldKeepOldVersionWhenGenerationFails` 等在新桩下行为不变。）

追加用例：

```java
    @Test
    void shouldPublishSiteUpdatedEventWithDiffCountsWhenSecondVersion() {
        AutoSite site = site(1L);
        when(enhancedService.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyString())).thenReturn(XML);
        when(progressService.getTaskResult(anyString())).thenReturn(result(7));
        io.github.ghgongjin.sitemap.entity.AutoSiteVersion v2 = successVersion(2);
        v2.setDiffAdded(3);
        v2.setDiffRemoved(1);
        v2.setDiffChanged(2);
        when(autoSiteService.latestVersion(1L)).thenReturn(Optional.of(v2));
        io.github.ghgongjin.sitemap.entity.SeoReport report = new io.github.ghgongjin.sitemap.entity.SeoReport();
        report.setErrorCount(5);
        when(seoReportService.save(anyString(), anyString(), any())).thenReturn(report);

        updater.update(site);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        SiteUpdatedEvent event = (SiteUpdatedEvent) captor.getValue();
        assertThat(event.siteId()).isEqualTo(1L);
        assertThat(event.versionNumber()).isEqualTo(2);
        assertThat(event.diffAdded()).isEqualTo(3);
        assertThat(event.seoErrorCount()).isEqualTo(5);
        assertThat(event.firstVersion()).isFalse();
    }

    @Test
    void shouldMarkFirstVersionWithoutSeoReportWhenNoReportSaved() {
        AutoSite site = site(1L);
        when(enhancedService.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyString())).thenReturn(XML);
        when(progressService.getTaskResult(anyString())).thenReturn(result(1));
        // latestVersion 默认桩为版本 1；seoReportService.save 默认返回 null

        updater.update(site);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        SiteUpdatedEvent event = (SiteUpdatedEvent) captor.getValue();
        assertThat(event.firstVersion()).isTrue();
        assertThat(event.seoErrorCount()).isNull();
    }

    @Test
    void shouldPublishSiteFailedEventWithConsecutiveCountWhenCrawlFails() {
        AutoSite site = site(1L);
        when(enhancedService.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyString())).thenThrow(new IllegalStateException("boom"));
        when(autoSiteService.recordFailure(anyLong(), any())).thenAnswer(inv -> failedSite(3));

        updater.update(site);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        SiteFailedEvent event = (SiteFailedEvent) captor.getValue();
        assertThat(event.siteId()).isEqualTo(1L);
        assertThat(event.consecutiveFailures()).isEqualTo(3);
    }

    @Test
    void shouldStillRecordSuccessWhenEventPublishThrows() {
        AutoSite site = site(1L);
        when(enhancedService.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyString())).thenReturn(XML);
        when(progressService.getTaskResult(anyString())).thenReturn(result(1));
        org.mockito.Mockito.doThrow(new RuntimeException("publisher down"))
                .when(events).publishEvent(org.mockito.ArgumentMatchers.any());

        assertThat(updater.update(site)).isTrue();
        verify(pushService).push(1L);
    }
```

- [ ] **Step 2: 跑红**

Run: `mvn -o test -Dtest=AutoSiteUpdaterTest` → 编译失败（事件 record/新构造签名不存在）。

- [ ] **Step 3: 实现**

两个事件 record（包 `io.github.ghgongjin.sitemap.service.notify`，各带类头注释）：

```java
public record SiteUpdatedEvent(Long siteId, Long versionId, int versionNumber,
                               int diffAdded, int diffRemoved, int diffChanged,
                               Integer seoErrorCount, boolean firstVersion) {
}
```

```java
public record SiteFailedEvent(Long siteId, int consecutiveFailures, String message) {
}
```

`AutoSiteUpdater`：字段追加 `private final ApplicationEventPublisher events;`；`update` 成功分支在 `saveSeoReport(...)` 后加 `publishUpdatedEvent(site, report)`（`saveSeoReport` 返回类型 `void` → `SeoReport`，方法体不变、末尾 `return` 原调用结果）；catch 分支：

```java
        } catch (Exception e) {
            log.error("自动更新失败：{}，原因：{}", site.getUrl(), e.getMessage());
            AutoSite failed = autoSiteService.recordFailure(site.getId(), e.getMessage());
            publishEvent(new SiteFailedEvent(site.getId(),
                    failed.consecutiveFailuresOrZero(), failed.getLastMessage()));
            return false;
        }
```

新增两个私有方法：

```java
    private void publishUpdatedEvent(AutoSite site, SeoReport report) {
        try {
            autoSiteService.latestVersion(site.getId()).ifPresent(version -> publishEvent(
                    new SiteUpdatedEvent(site.getId(), version.getId(), version.getVersionNumber(),
                            version.getDiffAdded(), version.getDiffRemoved(), version.getDiffChanged(),
                            report == null ? null : report.getErrorCount(),
                            version.getVersionNumber() == 1)));
        } catch (Exception e) {
            log.warn("站点更新事件发布失败：siteId={}，{}", site.getId(), e.getMessage());
        }
    }

    /** 事件发布异常绝不影响更新结果（监听器异步，此处仅防御发布环节本身） */
    private void publishEvent(Object event) {
        try {
            events.publishEvent(event);
        } catch (Exception e) {
            log.warn("通知事件发布异常：{}", e.getMessage());
        }
    }
```

catch 分支里的 `recordFailure` 已能返回值；若 `recordFailure` 本身抛异常（库挂）则沿用现状向上传播给调度器。
`AutoSiteScheduler` 等构造 `AutoSiteUpdater` 处均为 Spring 注入，无手工 new 遗漏（编译器兜底；测试里只有 `AutoSiteUpdaterTest` 手工 new）。

- [ ] **Step 4: 跑绿 + 相关回归**

Run: `mvn -o test -Dtest=AutoSiteUpdaterTest+AutoSiteScheduler*Test` → 全 PASS（调度器测试若手工构造 updater 需同步加 `events` 桩，编译器会暴露）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/ghgongjin/sitemap/service/notify src/main/java/io/github/ghgongjin/sitemap/service/AutoSiteUpdater.java src/test/java/io/github/ghgongjin/sitemap/service/AutoSiteUpdaterTest.java
git commit -m "feat: 自动更新成功/失败发布 SiteUpdatedEvent/SiteFailedEvent（发布失败不影响主流程）"
```

---

### Task 6: WebhookUrlPolicy（SSRF 独立策略）

**Files:**
- Create: `src/main/java/io/github/ghgongjin/sitemap/service/notify/WebhookUrlPolicy.java`
- Test: `src/test/java/io/github/ghgongjin/sitemap/service/notify/WebhookUrlPolicyTest.java`

**Interfaces:**
- Produces:

```java
public enum WebhookUrlCheck { OK, MALFORMED, FORBIDDEN_SCHEME, HAS_CREDENTIALS, DNS_FAILED, DENIED_ALWAYS, DENIED_PRIVATE }

public WebhookUrlPolicy(boolean allowPrivateNetwork)                      // 生产入口（NotificationConfig 建 Bean）
WebhookUrlPolicy(boolean allowPrivateNetwork, Function<String, InetAddress[]> resolver)  // 包私有测试缝
public WebhookUrlCheck check(String url)      // 永不抛异常，保存路径用（映射 i18n key）
public URI validate(String url)               // check != OK 时抛 SecurityException（英文技术消息，仅日志），发送路径用
```

`CrawlUrlPolicy` 保持零改动（评审红线）：本类是独立实现，允许 ~25 行分类逻辑重复，类注释注明与 CrawlUrlPolicy 的语义差异（私有网段可放行）。

- [ ] **Step 1: 写失败测试**

```java
package io.github.ghgongjin.sitemap.service.notify;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @ClassName WebhookUrlPolicyTest
 * @Description webhook 地址策略：仅 http(s)、禁凭据、元数据/链路本地无条件拒绝、私网按开关两态
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
class WebhookUrlPolicyTest {

    private static InetAddress[] ip(String literal) {
        try {
            return new InetAddress[]{InetAddress.getByName(literal)};
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static WebhookUrlPolicy allowingPrivate(String host, String... addresses) {
        return policy(true, host, addresses);
    }

    private static WebhookUrlPolicy policy(boolean allowPrivate, String host, String... addresses) {
        return new WebhookUrlPolicy(allowPrivate, h -> {
            if (!h.equals(host)) {
                throw new SecurityException("unexpected host");
            }
            return ip(addresses.length == 1 ? addresses[0] : addresses[0]);
        });
    }

    @Test
    void shouldAllowPublicHttps() {
        assertThat(allowingPrivate("example.com", "93.184.216.34")
                .check("https://example.com/hook")).isEqualTo(WebhookUrlCheck.OK);
    }

    @Test
    void shouldRejectNonHttpSchemeAndMalformedAndBlank() {
        WebhookUrlPolicy p = allowingPrivate("example.com", "93.184.216.34");
        assertThat(p.check("ftp://example.com/x")).isEqualTo(WebhookUrlCheck.FORBIDDEN_SCHEME);
        assertThat(p.check("not a url")).isEqualTo(WebhookUrlCheck.MALFORMED);
        assertThat(p.check(null)).isEqualTo(WebhookUrlCheck.MALFORMED);
        assertThat(p.check("")).isEqualTo(WebhookUrlCheck.MALFORMED);
    }

    @Test
    void shouldRejectUrlWithCredentials() {
        assertThat(allowingPrivate("example.com", "93.184.216.34")
                .check("https://user:pw@example.com/hook")).isEqualTo(WebhookUrlCheck.HAS_CREDENTIALS);
    }

    @Test
    void shouldDenyMetadataAndLinkLocalRegardlessOfPrivateSwitch() {
        // 169.254.0.0/16 链路本地/云元数据：allowPrivate=true 也拒绝
        assertThat(allowingPrivate("meta.example.com", "169.254.169.254")
                .check("http://meta.example.com/latest/user-data")).isEqualTo(WebhookUrlCheck.DENIED_ALWAYS);
        assertThat(policy(false, "meta.example.com", "169.254.169.254")
                .check("http://meta.example.com/x")).isEqualTo(WebhookUrlCheck.DENIED_ALWAYS);
    }

    @Test
    void shouldAllowLoopbackAndSiteLocalWhenPrivateAllowed() {
        assertThat(allowingPrivate("n8n.internal", "127.0.0.1")
                .check("http://n8n.internal:5678/webhook")).isEqualTo(WebhookUrlCheck.OK);
        assertThat(allowingPrivate("lan", "192.168.1.50")
                .check("http://lan/webhook")).isEqualTo(WebhookUrlCheck.OK);
        assertThat(allowingPrivate("lan6", "fec0::1")
                .check("http://lan6/webhook")).isEqualTo(WebhookUrlCheck.OK);
    }

    @Test
    void shouldDenyLoopbackAndSiteLocalWhenPrivateDisabled() {
        assertThat(policy(false, "n8n.internal", "127.0.0.1")
                .check("http://n8n.internal/webhook")).isEqualTo(WebhookUrlCheck.DENIED_PRIVATE);
        assertThat(policy(false, "lan", "10.0.0.8")
                .check("http://lan/webhook")).isEqualTo(WebhookUrlCheck.DENIED_PRIVATE);
    }

    @Test
    void shouldDenyWhenAnyResolvedAddressIsBlocked() {
        // 混合解析（公网+内网）在两种开关下都不可发送：私网开关开→按 OK 处理首个？否——逐地址判定，任一私网且开关关才 DENIED_PRIVATE；
        // 开关开时任一 DENIED_ALWAYS 即拒。混合公网+内网+allowPrivate=false：
        WebhookUrlPolicy p = new WebhookUrlPolicy(false, h -> new InetAddress[]{
                ip("93.184.216.34")[0], ip("192.168.0.1")[0]});
        assertThat(p.check("http://mixed.example/hook")).isEqualTo(WebhookUrlCheck.DENIED_PRIVATE);
    }

    @Test
    void shouldReportDnsFailure() {
        WebhookUrlPolicy p = new WebhookUrlPolicy(true, h -> {
            throw new RuntimeException("dns down");
        });
        assertThat(p.check("http://nonexistent.invalid/hook")).isEqualTo(WebhookUrlCheck.DNS_FAILED);
    }

    @Test
    void validateShouldThrowSecurityExceptionOnRejection() {
        WebhookUrlPolicy p = policy(false, "internal", "127.0.0.1");
        assertThat(p.validate("http://internal/hook").getHost()).isEqualTo("internal");
        assertThatThrownBy(() -> p.validate("http://nope.invalid"))
                .isInstanceOf(SecurityException.class);
    }
}
```

（`nope.invalid` 走默认 resolver 分支——`validateShouldThrowSecurityExceptionOnRejection` 中 `p` 注入的 resolver 对非 `internal` 主机抛 `SecurityException`，被 check 归为 DNS_FAILED → validate 包装为 SecurityException，满足断言。）

- [ ] **Step 2: 跑红** — `mvn -o test -Dtest=WebhookUrlPolicyTest` 编译失败。

- [ ] **Step 3: 实现**

```java
package io.github.ghgongjin.sitemap.service.notify;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.function.Function;

/**
 * @ClassName WebhookUrlPolicy
 * @Description Webhook 出站地址安全策略。与 CrawlUrlPolicy 语义不同、刻意独立：
 *              云元数据/链路本地/组播/未指定地址无条件拒绝；回环与私网由 allowPrivateNetwork
 *              控制（默认 true——自托管部署挂本机接收端是主场景，UI 有警示）。
 *              CrawlUrlPolicy 的爬取红线不受此处影响，两者互不引用。
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
public class WebhookUrlPolicy {

    public enum WebhookUrlCheck {
        OK, MALFORMED, FORBIDDEN_SCHEME, HAS_CREDENTIALS, DNS_FAILED, DENIED_ALWAYS, DENIED_PRIVATE
    }

    private final boolean allowPrivateNetwork;
    private final Function<String, InetAddress[]> resolver;

    public WebhookUrlPolicy(boolean allowPrivateNetwork) {
        this(allowPrivateNetwork, host -> {
            try {
                return InetAddress.getAllByName(host);
            } catch (Exception e) {
                throw new IllegalStateException("DNS failed: " + host);
            }
        });
    }

    WebhookUrlPolicy(boolean allowPrivateNetwork, Function<String, InetAddress[]> resolver) {
        this.allowPrivateNetwork = allowPrivateNetwork;
        this.resolver = resolver;
    }

    /**
     * 保存路径：不抛异常，返回可本地化的检查结论
     */
    public WebhookUrlCheck check(String url) {
        URI uri;
        try {
            uri = new URI(url == null ? null : url.trim());
        } catch (URISyntaxException | NullPointerException e) {
            return WebhookUrlCheck.MALFORMED;
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            return WebhookUrlCheck.MALFORMED;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return WebhookUrlCheck.FORBIDDEN_SCHEME;
        }
        if (uri.getUserInfo() != null) {
            return WebhookUrlCheck.HAS_CREDENTIALS;
        }
        InetAddress[] resolved;
        try {
            resolved = resolver.apply(uri.getHost());
        } catch (Exception e) {
            return WebhookUrlCheck.DNS_FAILED;
        }
        if (resolved == null || resolved.length == 0) {
            return WebhookUrlCheck.DNS_FAILED;
        }
        for (InetAddress address : resolved) {
            if (deniedAlways(address)) {
                return WebhookUrlCheck.DENIED_ALWAYS;
            }
            if (!allowPrivateNetwork && privateAddress(address)) {
                return WebhookUrlCheck.DENIED_PRIVATE;
            }
        }
        return WebhookUrlCheck.OK;
    }

    /**
     * 发送路径：违规抛 SecurityException（消息仅进日志）
     */
    public URI validate(String url) {
        WebhookUrlCheck check = check(url);
        if (check != WebhookUrlCheck.OK) {
            throw new SecurityException("webhook url rejected: " + check);
        }
        return URI.create(url.trim());
    }

    /** 云元数据/链路本地/组播/未指定：任何开关下都拒绝 */
    private boolean deniedAlways(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet4Address ipv4) {
            return (ipv4.getAddress()[0] & 0xFF) == 169 && (ipv4.getAddress()[1] & 0xFF) == 254;
        }
        return false;
    }

    /** 回环 + 站点本地 + CGN/基准测试 + IPv6 ULA；仅 allowPrivateNetwork=false 时拒绝 */
    private boolean privateAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        if (address instanceof Inet4Address ipv4) {
            int first = ipv4.getAddress()[0] & 0xFF;
            int second = ipv4.getAddress()[1] & 0xFF;
            return (first == 100 && second >= 64 && second <= 127)
                    || (first == 198 && (second == 18 || second == 19));
        }
        byte[] bytes = address.getAddress();
        return bytes != null && bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
```

- [ ] **Step 4: 跑绿** — `mvn -o test -Dtest=WebhookUrlPolicyTest` 全 PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/ghgongjin/sitemap/service/notify/WebhookUrlPolicy.java src/test/java/io/github/ghgongjin/sitemap/service/notify/WebhookUrlPolicyTest.java
git commit -m "feat: WebhookUrlPolicy——webhook 出站独立 SSRF 策略（元数据永拒，私网可开关）"
```

---

### Task 7: 通道契约与 WebhookSender

**Files:**
- Create: `service/notify/NotificationType.java`、`NotificationPayload.java`、`NotifyChannel.java`、`NotifyOutcome.java`、`WebhookSender.java`
- Modify: `config/NotificationConfig.java`（Task 9 建；本任务先建只含 `@Bean WebhookUrlPolicy` 的最简版本并把 `@EnableAsync` 与线程池留到 Task 9——**为避免两任务改同一文件**，本任务先建完整 Config，Task 9 不再改）→ 实际拆分：`NotificationConfig.java` 在本任务创建（含 `webhookUrlPolicy` 与 `notifyExecutor` 两个 Bean、`@EnableAsync`），Task 9 仅消费。
- Test: `src/test/java/io/github/ghgongjin/sitemap/service/notify/WebhookSenderTest.java`、`config/NotificationConfigTest.java`

**Interfaces:**
- Consumes: `WebhookUrlPolicy`（T6）、`CredentialCipher.encrypt/decrypt`（既有）、`NotifyProperties`（T1）、`AutoSite.notify*`（T2）。
- Produces:

```java
public enum NotificationType { CHANGED, FAILED, TEST }

public record NotificationPayload(NotificationType type, String siteUrl, Integer versionNumber,
        int added, int removed, int changed, Integer seoErrorCount, Integer seoErrorThreshold,
        Integer consecutiveFailures, String failureMessage, java.time.LocalDateTime occurredAt) {

    static NotificationPayload ofChanged(AutoSite site, SiteUpdatedEvent e) {...}
    static NotificationPayload ofFailed(AutoSite site, SiteFailedEvent e) {...}
    static NotificationPayload ofTest(AutoSite site) {...}   // type=TEST，其余取站点配置阈值，计数全 0
}

public interface NotifyChannel {
    String name();          // "webhook" | "email"
    boolean send(AutoSite site, NotificationPayload payload);  // false=失败/未送达；不抛业务异常
}

public record NotifyOutcome(boolean success, String messageKey, String detail) {}
```

`WebhookSender`（`@Component`，`name()="webhook"`）构造：`@Autowired public WebhookSender(WebhookUrlPolicy, NotifyProperties, CredentialCipher, ObjectMapper)`，包私有五参构造末位注入 `Transport` 测试缝：

```java
    interface Transport {
        /** 非 2xx 与网络异常一律抛出 */
        void postJson(String url, String body, String signatureHeader, int timeoutMs) throws Exception;
    }
```

签名：secret 未配置→请求不带 `X-Sitemap-Signature` 头；配置了→值 `sha256=<hmacSha256Hex(body, secret)>`（hex 小写，HMAC 对**字节完全一致的 body 字符串**计算）。body 为 Spring 管理 `ObjectMapper` 序列化的 payload（Boot 已注册 JavaTimeModule，`occurredAt` 输出 ISO-8601）。重试：失败 sleep 2000ms 再 1 次，共 2 次尝试。

- [ ] **Step 1: 写失败测试**

`NotificationConfigTest`：

```java
package io.github.ghgongjin.sitemap.config;

import io.github.ghgongjin.sitemap.service.notify.WebhookUrlPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName NotificationConfigTest
 * @Description 通知底座装配：策略 Bean 与 notifyExecutor 线程池参数（2 线程/队列 100）
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationConfigTest {

    @Autowired
    private TaskExecutor notifyExecutor;

    @Autowired
    private WebhookUrlPolicy webhookUrlPolicy;

    @Test
    void shouldConfigureNotifyExecutorWithTwoThreadsAndQueue100() {
        assertThat(webhookUrlPolicy).isNotNull();
        assertThat(notifyExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) notifyExecutor;
        assertThat(pool.getCorePoolSize()).isEqualTo(2);
        assertThat(pool.getMaxPoolSize()).isEqualTo(2);
        assertThat(pool.getThreadPriority()).isPositive();
    }
}
```

`WebhookSenderTest`：

```java
package io.github.ghgongjin.sitemap.service.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ghgongjin.sitemap.config.NotifyProperties;
import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.service.CredentialCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName WebhookSenderTest
 * @Description webhook 通道：URL 策略拦截、JSON 载荷、HMAC 签名头、失败重试 1 次
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
class WebhookSenderTest {

    record Call(String url, String body, String signature, int timeoutMs) {}

    static class FakeTransport implements WebhookSender.Transport {
        final List<Call> calls = new ArrayList<>();
        int failuresBeforeSuccess;
        RuntimeException error = new RuntimeException("connection reset");

        @Override
        public void postJson(String url, String body, String signature, int timeoutMs) throws Exception {
            calls.add(new Call(url, body, signature, timeoutMs));
            if (calls.size() <= failuresBeforeSuccess) {
                throw error;
            }
        }
    }

    @TempDir
    Path tmp;

    private WebhookSender sender(FakeTransport transport, boolean allowPrivate) {
        NotifyProperties props = new NotifyProperties();
        props.setAllowPrivateNetwork(allowPrivate);
        CredentialCipher cipher = new CredentialCipher("", tmp.resolve("push.key").toString());
        return new WebhookSender(new WebhookUrlPolicy(allowPrivate, host -> new InetAddress[]{loopback()}),
                props, cipher, new ObjectMapper(), transport);
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static AutoSite siteWithWebhook(String secretEnc) {
        AutoSite site = new AutoSite();
        site.setId(1L);
        site.setUrl("https://example.com");
        site.setNotifyWebhookUrl("http://n8n.internal:5678/webhook");
        site.setNotifyWebhookSecretEnc(secretEnc);
        return site;
    }

    private static NotificationPayload payload() {
        SiteUpdatedEvent event = new SiteUpdatedEvent(1L, 2L, 2, 3, 1, 2, 5, false);
        return NotificationPayload.ofChanged(siteWithWebhook(null), event);
    }

    @Test
    void shouldSendSignedJsonWhenSecretConfigured() {
        // Given：secret 明文 "s3cr3t" 经 CredentialCipher 加密
        CredentialCipher cipher = new CredentialCipher("", tmp.resolve("push.key").toString());
        FakeTransport transport = new FakeTransport();
        WebhookSender sender = sender(transport, true);
        AutoSite site = siteWithWebhook(cipher.encrypt("s3cr3t"));

        assertThat(sender.send(site, payload())).isTrue();

        Call call = transport.calls.get(0);
        assertThat(call.body()).contains("\"type\":\"CHANGED\"").contains("\"added\":3");
        String expected = "sha256=" + hmacHex("s3cr3t", call.body());
        assertThat(call.signature()).isEqualTo(expected);
        assertThat(call.timeoutMs()).isEqualTo(10000);
    }

    @Test
    void shouldOmitSignatureHeaderWhenNoSecret() {
        FakeTransport transport = new FakeTransport();
        WebhookSender sender = sender(transport, true);

        assertThat(sender.send(siteWithWebhook(null), payload())).isTrue();
        assertThat(transport.calls.get(0).signature()).isNull();
    }

    @Test
    void shouldRetryOnceThenSucceedAndGiveUpAfterSecondFailure() {
        FakeTransport ok = new FakeTransport();
        ok.failuresBeforeSuccess = 1;
        assertThat(sender(ok, true).send(siteWithWebhook(null), payload())).isTrue();
        assertThat(ok.calls).hasSize(2);

        FakeTransport dead = new FakeTransport();
        dead.failuresBeforeSuccess = 5;
        assertThat(sender(dead, true).send(siteWithWebhook(null), payload())).isFalse();
        assertThat(dead.calls).hasSize(2);
    }

    @Test
    void shouldRefuseToSendWhenPolicyRejectsUrl() {
        FakeTransport transport = new FakeTransport();
        WebhookSender sender = sender(transport, false);   // 私网关闭，站点配的是内网地址

        assertThat(sender.send(siteWithWebhook(null), payload())).isFalse();
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void shouldFailQuietlyWhenSecretUndecryptable() {
        FakeTransport transport = new FakeTransport();
        WebhookSender sender = sender(transport, true);

        assertThat(sender.send(siteWithWebhook("garbage-not-a-ciphertext"), payload())).isFalse();
        assertThat(transport.calls).isEmpty();
    }

    private static String hmacHex(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder();
        for (byte b : mac.doFinal(body.getBytes(StandardCharsets.UTF_8))) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
```

- [ ] **Step 2: 跑红** — 编译失败。

- [ ] **Step 3: 实现**（`NotificationType`/`NotifyChannel`/`NotifyOutcome` 按接口块逐字写；`NotificationPayload` 工厂方法：）

```java
    public static NotificationPayload ofChanged(AutoSite site, SiteUpdatedEvent e) {
        return new NotificationPayload(NotificationType.CHANGED, site.getUrl(), e.versionNumber(),
                e.diffAdded(), e.diffRemoved(), e.diffChanged(),
                e.seoErrorCount(), site.notifySeoErrorThresholdOrOff(), null, null, LocalDateTime.now());
    }

    public static NotificationPayload ofFailed(AutoSite site, SiteFailedEvent e) {
        return new NotificationPayload(NotificationType.FAILED, site.getUrl(), null,
                0, 0, 0, null, site.notifySeoErrorThresholdOrOff(),
                e.consecutiveFailures(), e.message(), LocalDateTime.now());
    }

    public static NotificationPayload ofTest(AutoSite site) {
        return new NotificationPayload(NotificationType.TEST, site.getUrl(), null,
                0, 0, 0, null, site.notifySeoErrorThresholdOrOff(), null, null, LocalDateTime.now());
    }
```

`WebhookSender` 核心：

```java
    @Override
    public boolean send(AutoSite site, NotificationPayload payload) {
        if (site.getNotifyWebhookUrl() == null || site.getNotifyWebhookUrl().isBlank()) {
            return false;
        }
        try {
            urlPolicy.validate(site.getNotifyWebhookUrl());
            String body = objectMapper.writeValueAsString(payload);
            String signature = signature(site, body);
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    transport.postJson(site.getNotifyWebhookUrl(), body, signature, props.getWebhookTimeoutMs());
                    return true;
                } catch (Exception e) {
                    log.warn("Webhook 投递第 {} 次失败：siteId={}，{}", attempt, site.getId(), e.getMessage());
                    if (attempt == 1) {
                        Thread.sleep(RETRY_DELAY_MS);
                    }
                }
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("Webhook 发送中止：siteId={}，{}", site.getId(), e.getMessage());
            return false;
        }
    }

    private String signature(AutoSite site, String body) {
        if (site.getNotifyWebhookSecretEnc() == null || site.getNotifyWebhookSecretEnc().isBlank()) {
            return null;
        }
        try {
            return "sha256=" + hmacSha256Hex(cipher.decrypt(site.getNotifyWebhookSecretEnc()), body);
        } catch (IllegalStateException e) {
            throw new SecurityException("webhook secret decrypt failed");
        }
    }

    static String hmacSha256Hex(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder();
        for (byte b : mac.doFinal(body.getBytes(StandardCharsets.UTF_8))) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
```
（与测试中 `hmacHex` 逐字同实现，测试即验证件。）

`RestClientTransport`（默认实现，内部类或同包类）：

```java
    static class RestClientTransport implements Transport {
        @Override
        public void postJson(String url, String body, String signature, int timeoutMs) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
            factory.setReadTimeout(Duration.ofMillis(timeoutMs));
            RestClient client = RestClient.builder().requestFactory(factory).build();
            client.post().uri(URI.create(url))
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (signature != null) {
                            headers.set("X-Sitemap-Signature", signature);
                        }
                    })
                    .body(body)
                    .retrieve().toBodilessEntity();   // 非 2xx 抛 RestClientResponseException
        }
    }
```

`NotificationConfig`（本任务一并创建，含池 Bean——@EnableAsync 留此，Task 9 不再动）：

```java
package io.github.ghgongjin.sitemap.config;

import io.github.ghgongjin.sitemap.service.notify.WebhookUrlPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;

/**
 * @ClassName NotificationConfig
 * @Description 告警底座：webhook 策略 Bean + notifyExecutor 专用小池（2 线程/队列 100，满丢最旧），
 *              与爬取/推送线程完全隔离；丢最旧的兜底分支只丢新任务，保证不递归
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
@Slf4j
@Configuration
@EnableAsync
@RequiredArgsConstructor
public class NotificationConfig {

    private final NotifyProperties props;

    @Bean
    public WebhookUrlPolicy webhookUrlPolicy() {
        return new WebhookUrlPolicy(props.isAllowPrivateNetwork());
    }

    @Bean(name = "notifyExecutor")
    public ThreadPoolTaskExecutor notifyExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("notify-");
        executor.setRejectedExecutionHandler((runnable, pool) -> {
            if (pool.getQueue().poll() != null) {
                log.warn("通知队列已满，丢弃最早的一条通知");
                try {
                    pool.execute(runnable);
                } catch (RejectedExecutionException e) {
                    log.warn("通知任务被丢弃（二次拒绝）");
                }
            } else {
                log.warn("通知队列已满且无可丢旧任务，丢弃新通知");
            }
        });
        executor.initialize();
        return executor;
    }
}
```

（`ThreadPoolTaskExecutor` 同时是 `TaskExecutor`，`@Async("notifyExecutor")` 按名解析。测试里 `@Autowired TaskExecutor notifyExecutor` 按名匹配。`getThreadPriority` 断言若无意义可去掉——保留 `getCorePoolSize` 等三项即可，实现者按测试类为准。）

- [ ] **Step 4: 跑绿** — `mvn -o test -Dtest=WebhookSenderTest+NotificationConfigTest` → 全 PASS。注意 `shouldRetryOnceThenSucceed...` 含一次 2s sleep，整类耗秒级属预期。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/ghgongjin/sitemap/service/notify src/main/java/io/github/ghgongjin/sitemap/config/NotificationConfig.java src/test/java/io/github/ghgongjin/sitemap/service/notify src/test/java/io/github/ghgongjin/sitemap/config/NotificationConfigTest.java
git commit -m "feat: 通知通道契约与 WebhookSender（HMAC 签名/超时/重试 1 次）+ notifyExecutor 线程池"
```

---

### Task 8: MailSender 与邮件模板

**Files:**
- Create: `src/main/java/io/github/ghgongjin/sitemap/service/notify/MailSender.java`
- Create: `src/main/resources/templates/mail/notify-changed.html`、`notify-failed.html`、`notify-test.html`
- Test: `src/test/java/io/github/ghgongjin/sitemap/service/notify/MailSenderTest.java`

**Interfaces:**
- Consumes: `NotifyChannel`/`NotificationPayload`（T7）、`AutoSite.notifyEmail`（T2）。
- Produces: `@Component @ConditionalOnProperty(name = "spring.mail.host")` 的 `MailSender implements NotifyChannel`，`name()="email"`；构造 `(JavaMailSender, org.thymeleaf.TemplateEngine, @Value("${spring.mail.host:}") String hostName, @Value("${spring.mail.username:}") String username)`。Thymeleaf `SpringTemplateEngine extends org.thymeleaf.TemplateEngine`（已用 javap 核实 3.1.5），按接口注入即可；测试用裸 `org.thymeleaf.TemplateEngine` + `ClassLoaderTemplateResolver(prefix="templates/", suffix=".html", UTF-8)`。
- 邮件为 text+html multipart（`MimeMessageHelper.setText(text, html)`）；模板仅用 `${p.*}` 变量与固定双语行，**不用 `#{}`**（收件人无请求 locale，避开语言解析；固定行 zh+en 并列）。
- 主题固定双语：CHANGED→"站点有更新 / Site updated"；FAILED→"站点更新失败 / Site update failed"；TEST→"测试通知 / Test notification"，前缀 `[Sitemap Studio] `。

- [ ] **Step 1: 写失败测试**

```java
package io.github.ghgongjin.sitemap.service.notify;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @ClassName MailSenderTest
 * @Description 邮件通道：无收件人跳过、multipart 渲染含计数与站点地址、异常吞为 false
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
class MailSenderTest {

    private static TemplateEngine templateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private static MailSender sender(JavaMailSenderImpl mailSender) {
        return new MailSender(mailSender, templateEngine(), "smtp.example.com", "bot@example.com");
    }

    private static AutoSite siteWithEmail() {
        AutoSite site = new AutoSite();
        site.setId(1L);
        site.setUrl("https://example.com");
        site.setNotifyEmail("owner@example.com");
        return site;
    }

    private static MimeMessage freshMessage(JavaMailSenderImpl mailSender) {
        JavaMailSenderImpl offline = new JavaMailSenderImpl();
        offline.setHost("localhost");
        return offline.createMimeMessage();
    }

    @Test
    void shouldSkipWhenNoRecipientConfigured() {
        JavaMailSenderImpl mailSender = mock(JavaMailSenderImpl.class);
        AutoSite site = new AutoSite();
        site.setId(1L);
        site.setUrl("https://example.com");

        assertThat(sender(mailSender).send(site,
                new NotificationPayload(NotificationType.TEST, "https://example.com", null,
                        0, 0, 0, null, -1, null, null, LocalDateTime.now()))).isFalse();
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void shouldSendMultipartWithCountsAndSiteUrl() throws Exception {
        JavaMailSenderImpl mailSender = mock(JavaMailSenderImpl.class);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> freshMessage(mailSender));
        NotificationPayload payload = new NotificationPayload(NotificationType.CHANGED,
                "https://example.com", 2, 3, 1, 2, 5, 3, null, null, LocalDateTime.now());

        assertThat(sender(mailSender).send(siteWithEmail(), payload)).isTrue();

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        assertThat(sent.getHeader("Subject")).isNotNull();
        String content = readAll(sent);
        assertThat(content).contains("https://example.com").contains("+3").contains("-1").contains("~2");
        // HTML part 由 Thymeleaf 渲染
        assertThat(content).contains("multipart/alternative");
    }

    @Test
    void shouldReturnFalseWhenSendThrows() throws Exception {
        JavaMailSenderImpl mailSender = mock(JavaMailSenderImpl.class);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> freshMessage(mailSender));
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("smtp down"))
                .when(mailSender).send(any(MimeMessage.class));
        NotificationPayload payload = new NotificationPayload(NotificationType.FAILED,
                "https://example.com", null, 0, 0, 0, null, -1, 1, "connect timeout", LocalDateTime.now());

        assertThat(sender(mailSender).send(siteWithEmail(), payload)).isFalse();
    }

    private static String readAll(MimeMessage message) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        message.writeTo(out);
        return new String(out.toByteArray(), StandardCharsets.ISO_8859_1); // multipart 边界/文本 part 为 quoted-printable，URL 与数字直出
    }
}
```

- [ ] **Step 2: 跑红** — 编译失败。

- [ ] **Step 3: 实现 MailSender**

```java
package io.github.ghgongjin.sitemap.service.notify;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

/**
 * @ClassName MailSender
 * @Description 邮件通知通道：仅当部署者配置 spring.mail.host 时装配；
 *              text+html multipart，模板固定双语、只渲染变量，HTML 转义交 Thymeleaf
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.mail.host")
public class MailSender implements NotifyChannel {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final String from;

    public MailSender(JavaMailSender mailSender, TemplateEngine templateEngine,
                      @Value("${spring.mail.host:}") String host,
                      @Value("${spring.mail.username:}") String username) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.from = username == null || username.isBlank() ? "sitemap-studio@" + host : username;
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public boolean send(AutoSite site, NotificationPayload payload) {
        if (site.getNotifyEmail() == null || site.getNotifyEmail().isBlank()) {
            return false;
        }
        try {
            Context context = new Context(java.util.Locale.ENGLISH, Map.of("p", payload));
            String html = templateEngine.process(templateFor(payload.type()), context);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(site.getNotifyEmail());
            helper.setSubject("[Sitemap Studio] " + subject(payload.type()));
            helper.setText(plainText(payload), html);
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.warn("邮件通知发送失败：siteId={}，{}", site.getId(), e.getMessage());
            return false;
        }
    }

    private static String templateFor(NotificationType type) {
        return switch (type) {
            case CHANGED -> "mail/notify-changed";
            case FAILED -> "mail/notify-failed";
            case TEST -> "mail/notify-test";
        };
    }

    private static String subject(NotificationType type) {
        return switch (type) {
            case CHANGED -> "站点有更新 / Site updated";
            case FAILED -> "站点更新失败 / Site update failed";
            case TEST -> "测试通知 / Test notification";
        };
    }

    private static String plainText(NotificationPayload p) {
        StringBuilder sb = new StringBuilder();
        sb.append("站点 Site: ").append(p.siteUrl()).append('\n');
        if (p.versionNumber() != null) {
            sb.append("版本 Version: v").append(p.versionNumber()).append('\n');
            sb.append("变化 Changes: +").append(p.added())
                    .append(" / -").append(p.removed())
                    .append(" / ~").append(p.changed()).append('\n');
        }
        if (p.consecutiveFailures() != null) {
            sb.append("连续失败 Consecutive failures: ").append(p.consecutiveFailures())
                    .append("  原因 reason: ").append(p.failureMessage()).append('\n');
        }
        if (p.seoErrorCount() != null) {
            sb.append("SEO 错误 Errors: ").append(p.seoErrorCount())
                    .append("  阈值 threshold: ").append(p.seoErrorThreshold()).append('\n');
        }
        sb.append("详情见站点详情页 / See the site detail page in Sitemap Studio.\n");
        return sb.toString();
    }
}
```

三个 HTML 模板（结构相同，仅行不同；以 changed 为例）：

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
  <h3 th:text="'站点有更新 / Site updated'"></h3>
  <p><b th:text="${p.siteUrl}">https://example.com</b></p>
  <p th:text="'版本 Version: v' + ${p.versionNumber}"></p>
  <p th:text="'新增 Added: +' + ${p.added} + ' / 删除 Removed: -' + ${p.removed} + ' / 改动 Changed: ~' + ${p.changed}"></p>
  <p th:if="${p.seoErrorCount != null}" th:text="'SEO 错误 Errors: ' + ${p.seoErrorCount} + ' / 阈值 Threshold: ' + ${p.seoErrorThreshold}"></p>
  <p style="color:#888;font-size:12px" th:text="'Sitemap Studio · ' + ${p.occurredAt}"></p>
</body>
</html>
```

（`notify-failed.html` 展示 `consecutiveFailures` 与 `failureMessage`；`notify-test.html` 仅站点地址 + 「这是一条测试通知 / This is a test notification」。三模板时间一律 `${p.occurredAt}` 直出——`#temporals` 在裸 TemplateEngine 下不可用。）

- [ ] **Step 4: 跑绿 + 装配回归**

Run: `mvn -o test -Dtest=MailSenderTest+NotifyPropertiesBindingTest` → PASS（后者证明未配 spring.mail.host 时上下文可启动）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/ghgongjin/sitemap/service/notify/MailSender.java src/main/resources/templates/mail src/test/java/io/github/ghgongjin/sitemap/service/notify/MailSenderTest.java
git commit -m "feat: MailSender 邮件通道（条件装配，Thymeleaf text+html 双语模板）"
```

---

### Task 9: NotificationService 触发矩阵

**Files:**
- Create: `src/main/java/io/github/ghgongjin/sitemap/service/notify/NotificationService.java`
- Test: `src/test/java/io/github/ghgongjin/sitemap/service/notify/NotificationServiceTest.java`

**Interfaces:**
- Consumes: 事件（T5）、`NotifyChannel`/`NotificationPayload`（T7/T8 实现由容器注入 `List<NotifyChannel>`——**注意：无 SMTP 时列表只含 webhook**）、`AutoSiteRepository`、`AutoSiteVersionRepository`、`SeoReportRepository`、`NotifyProperties`。
- Produces: `@EventListener @Async("notifyExecutor") onSiteUpdated(SiteUpdatedEvent)` / `onSiteFailed(SiteFailedEvent)`；包可见同步入口 `void handleUpdated(SiteUpdatedEvent)` / `void handleFailed(SiteFailedEvent)`（测试直调）；`NotifyOutcome test(Long siteId)`。触发裁定：
  - CHANGED = 非首版 且 [ (URL 集合有变化 且 notifyOnChange 生效) 或 (SEO 边沿跨越) ]；**SEO 阈值独立于 onChange 开关**（自己就是显式开关）。
  - SEO 边沿：`threshold ≥ 0 && seoErrorCount ≥ threshold && 上一版报告 errorCount < threshold`；上一版无版本/无报告 → 视为 0。
  - FAILED = notifyOnFailure 生效 且 `consecutiveFailures ∈ {1,3,10}`（`static final Set<Integer> FAILURE_REMINDERS`）。
  - 邮件配额：`maxEmailsPerSitePerDay`（键 siteId+LocalDate，`ConcurrentHashMap<String, AtomicInteger>`，跨日清旧键）；超限当日邮件跳过、webhook 照常；`test()` 不计配额（端点自有 3 次/分钟令牌桶）。
  - 全局 `props.enabled=false` 或站点两渠道皆未配置 → 即早退。每渠道 send 外层 try/catch WARN。

- [ ] **Step 1: 写失败测试**

```java
package io.github.ghgongjin.sitemap.service.notify;

import io.github.ghgongjin.sitemap.config.NotifyProperties;
import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.entity.AutoSiteVersion;
import io.github.ghgongjin.sitemap.entity.SeoReport;
import io.github.ghgongjin.sitemap.repository.AutoSiteRepository;
import io.github.ghgongjin.sitemap.repository.AutoSiteVersionRepository;
import io.github.ghgongjin.sitemap.repository.SeoReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @ClassName NotificationServiceTest
 * @Description 触发矩阵：三条件 × 开关 × 边沿规则 × 邮件配额（通道用真实假实现记录调用）
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
class NotificationServiceTest {

    static class FakeChannel implements NotifyChannel {
        private final String name;
        final List<NotificationPayload> sent = new ArrayList<>();

        FakeChannel(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean send(AutoSite site, NotificationPayload payload) {
            sent.add(payload);
            return true;
        }
    }

    private AutoSiteRepository siteRepository;
    private AutoSiteVersionRepository versionRepository;
    private SeoReportRepository seoReportRepository;
    private NotifyProperties props;
    private FakeChannel webhook;
    private FakeChannel email;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        siteRepository = mock(AutoSiteRepository.class);
        versionRepository = mock(AutoSiteVersionRepository.class);
        seoReportRepository = mock(SeoReportRepository.class);
        props = new NotifyProperties();
        webhook = new FakeChannel("webhook");
        email = new FakeChannel("email");
        service = new NotificationService(siteRepository, versionRepository, seoReportRepository,
                List.of(webhook, email), props);
    }

    private AutoSite configuredSite() {
        AutoSite site = new AutoSite();
        site.setId(1L);
        site.setUrl("https://example.com");
        site.setNotifyWebhookUrl("http://n8n.internal/webhook");
        site.setNotifyEmail("owner@example.com");
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        return site;
    }

    private static SiteUpdatedEvent changed(int added, int removed, int changed, Integer seoErrors,
                                            int versionNumber, boolean first) {
        return new SiteUpdatedEvent(1L, 10L, versionNumber, added, removed, changed, seoErrors, first);
    }

    @Test
    void shouldDispatchBothChannelsWhenUrlChanged() {
        configuredSite();
        service.handleUpdated(changed(2, 0, 0, null, 2, false));
        assertThat(webhook.sent).hasSize(1);
        assertThat(email.sent).hasSize(1);
        assertThat(webhook.sent.get(0).type()).isEqualTo(NotificationType.CHANGED);
    }

    @Test
    void shouldStaySilentOnFirstVersionAndWhenNoChange() {
        configuredSite();
        service.handleUpdated(changed(0, 0, 0, null, 1, true));
        service.handleUpdated(changed(0, 0, 0, 1, 2, false));
        assertThat(webhook.sent).isEmpty();
        assertThat(email.sent).isEmpty();
    }

    @Test
    void shouldStaySilentWhenEveryChannelUnconfigured() {
        AutoSite site = new AutoSite();
        site.setId(1L);
        site.setUrl("https://example.com");
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        service.handleUpdated(changed(5, 0, 0, null, 2, false));
        assertThat(webhook.sent).isEmpty();
    }

    @Test
    void shouldRespectOnChangeSwitchButNotForSeoTrigger() {
        AutoSite site = configuredSite();
        site.setNotifyOnChange(false);
        service.handleUpdated(changed(5, 0, 0, null, 2, false));
        assertThat(webhook.sent).isEmpty();

        // SEO 跨越不受 onChange 开关抑制：阈值 3、本次 5、上一版报告 1
        site.setNotifySeoErrorThreshold(3);
        AutoSiteVersion previous = new AutoSiteVersion();
        previous.setTaskId("prev-task");
        when(versionRepository.findBySiteIdAndVersionNumber(1L, 1)).thenReturn(Optional.of(previous));
        SeoReport prevReport = new SeoReport();
        prevReport.setErrorCount(1);
        when(seoReportRepository.findByTaskId("prev-task")).thenReturn(Optional.of(prevReport));
        service.handleUpdated(changed(0, 0, 0, 5, 2, false));
        assertThat(webhook.sent).hasSize(1);
    }

    @Test
    void shouldNotRenotifySeoWhilePreviousVersionAlreadyAtOrAboveThreshold() {
        AutoSite site = configuredSite();
        site.setNotifySeoErrorThreshold(3);
        AutoSiteVersion previous = new AutoSiteVersion();
        previous.setTaskId("prev-task");
        when(versionRepository.findBySiteIdAndVersionNumber(1L, 1)).thenReturn(Optional.of(previous));
        SeoReport prevReport = new SeoReport();
        prevReport.setErrorCount(9);   // 上一版已跨：边沿不成立
        when(seoReportRepository.findByTaskId("prev-task")).thenReturn(Optional.of(prevReport));

        service.handleUpdated(changed(0, 0, 0, 5, 2, false));
        assertThat(webhook.sent).isEmpty();
    }

    @Test
    void shouldTreatMissingPreviousVersionOrReportAsZeroForEdgeTrigger() {
        AutoSite site = configuredSite();
        site.setNotifySeoErrorThreshold(3);
        when(versionRepository.findBySiteIdAndVersionNumber(anyLong(), anyLong())).thenReturn(Optional.empty());

        service.handleUpdated(changed(0, 0, 0, 4, 2, false));
        assertThat(webhook.sent).hasSize(1);
    }

    @Test
    void shouldNotifyFailureOnlyAtReminderCounts() {
        configuredSite();
        service.handleFailed(new SiteFailedEvent(1L, 2, "timeout"));
        assertThat(webhook.sent).isEmpty();
        service.handleFailed(new SiteFailedEvent(1L, 3, "timeout"));
        assertThat(webhook.sent).hasSize(1);
        assertThat(webhook.sent.get(0).type()).isEqualTo(NotificationType.FAILED);
        service.handleFailed(new SiteFailedEvent(1L, 11, "timeout"));
        assertThat(webhook.sent).hasSize(1);
    }

    @Test
    void shouldRespectFailureSwitchAndGlobalKillSwitch() {
        AutoSite site = configuredSite();
        site.setNotifyOnFailure(false);
        service.handleFailed(new SiteFailedEvent(1L, 1, "boom"));
        assertThat(webhook.sent).isEmpty();

        site.setNotifyOnFailure(true);
        props.setEnabled(false);
        service.handleFailed(new SiteFailedEvent(1L, 1, "boom"));
        service.handleUpdated(changed(9, 0, 0, null, 2, false));
        assertThat(webhook.sent).isEmpty();
    }

    @Test
    void shouldSkipEmailBeyondDailyQuotaButKeepWebhook() {
        configuredSite();
        props.setMaxEmailsPerSitePerDay(2);
        service.handleUpdated(changed(1, 0, 0, null, 2, false));
        service.handleUpdated(changed(1, 0, 0, null, 2, false));
        service.handleUpdated(changed(1, 0, 0, null, 2, false));
        assertThat(webhook.sent).hasSize(3);
        assertThat(email.sent).hasSize(2);
    }

    @Test
    void testShouldDeliverToConfiguredChannelsAndReportOutcome() {
        configuredSite();
        NotifyOutcome outcome = service.test(1L);
        assertThat(outcome.success()).isTrue();
        assertThat(webhook.sent).hasSize(1);
        assertThat(webhook.sent.get(0).type()).isEqualTo(NotificationType.TEST);
        assertThat(email.sent).hasSize(1);
    }

    @Test
    void testShouldFailWhenNoChannelConfiguredOrSiteMissing() {
        when(siteRepository.findById(1L)).thenReturn(Optional.empty());
        assertThat(service.test(1L).success()).isFalse();

        AutoSite bare = new AutoSite();
        bare.setId(1L);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(bare));
        NotifyOutcome outcome = service.test(1L);
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.messageKey()).isEqualTo("auto.notify.err.noChannel");
    }

    @Test
    void dispatchShouldSwallowChannelExceptions() {
        configuredSite();
        FakeChannel broken = new FakeChannel("webhook") {
            @Override
            public boolean send(AutoSite site, NotificationPayload payload) {
                throw new IllegalStateException("boom");
            }
        };
        NotificationService mixed = new NotificationService(siteRepository, versionRepository,
                seoReportRepository, List.of(broken, webhook), props);
        mixed.handleUpdated(changed(1, 0, 0, null, 2, false));
        assertThat(webhook.sent).hasSize(1);   // 一个通道炸不影响另一个
    }
}
```

（`findBySiteIdAndVersionNumber(anyLong(), anyLong())`——第二参数为 int，用 `anyInt()`；实现者按仓储接口实际签名 `findBySiteIdAndVersionNumber(Long, int)` 写 `anyInt()`。）

- [ ] **Step 2: 跑红** — 编译失败。

- [ ] **Step 3: 实现**

```java
package io.github.ghgongjin.sitemap.service.notify;

import io.github.ghgongjin.sitemap.config.NotifyProperties;
import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.repository.AutoSiteRepository;
import io.github.ghgongjin.sitemap.repository.AutoSiteVersionRepository;
import io.github.ghgongjin.sitemap.repository.SeoReportRepository;
import io.github.ghgongjin.sitemap.entity.SeoReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @ClassName NotificationService
 * @Description 告警唯一监听器：事件 → 触发判定（URL 变化 / 连续失败 1·3·10 / SEO 跨阈值边沿）→
 *              载荷组装 → 双通道分发。全部入口异步且吞异常，任何失败不影响爬取主流程
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    static final Set<Integer> FAILURE_REMINDERS = Set.of(1, 3, 10);

    private final AutoSiteRepository siteRepository;
    private final AutoSiteVersionRepository versionRepository;
    private final SeoReportRepository seoReportRepository;
    private final List<NotifyChannel> channels;   // import java.util.List
    private final NotifyProperties props;

    private final Map<String, AtomicInteger> mailQuota = new ConcurrentHashMap<>();

    @EventListener
    @Async("notifyExecutor")
    public void onSiteUpdated(SiteUpdatedEvent event) {
        handleUpdated(event);
    }

    @EventListener
    @Async("notifyExecutor")
    public void onSiteFailed(SiteFailedEvent event) {
        handleFailed(event);
    }

    void handleUpdated(SiteUpdatedEvent event) {
        if (!props.isEnabled() || event.firstVersion()) {
            return;
        }
        AutoSite site = findConfiguredSite(event.siteId());
        if (site == null) {
            return;
        }
        boolean urlTriggered = event.diffAdded() + event.diffRemoved() + event.diffChanged() > 0
                && site.isNotifyOnChangeEffective();
        if (!urlTriggered && !seoCrossed(site, event)) {
            return;
        }
        dispatch(site, NotificationPayload.ofChanged(site, event));
    }

    void handleFailed(SiteFailedEvent event) {
        if (!props.isEnabled()) {
            return;
        }
        AutoSite site = findConfiguredSite(event.siteId());
        if (site == null || !site.isNotifyOnFailureEffective()
                || !FAILURE_REMINDERS.contains(event.consecutiveFailures())) {
            return;
        }
        dispatch(site, NotificationPayload.ofFailed(site, event));
    }

    public NotifyOutcome test(Long siteId) {
        AutoSite site = siteId == null ? null : siteRepository.findById(siteId).orElse(null);
        if (site == null) {
            return new NotifyOutcome(false, "auto.error.notFound", null);
        }
        if (!site.hasNotifyChannelConfigured()) {
            return new NotifyOutcome(false, "auto.notify.err.noChannel", null);
        }
        NotificationPayload payload = NotificationPayload.ofTest(site);
        boolean delivered = false;
        for (NotifyChannel channel : channels) {
            if (!applies(channel, site)) {
                continue;
            }
            try {
                delivered |= channel.send(site, payload);
            } catch (Exception e) {
                log.warn("测试通知通道异常：channel={}，{}", channel.name(), e.getMessage());
            }
        }
        return delivered
                ? new NotifyOutcome(true, "auto.notify.flash.testSent", null)
                : new NotifyOutcome(false, "auto.notify.flash.testFailed", "投递未成功 delivery attempted but failed");
    }

    private boolean seoCrossed(AutoSite site, SiteUpdatedEvent event) {
        int threshold = site.notifySeoErrorThresholdOrOff();
        if (threshold < 0 || event.seoErrorCount() == null || event.seoErrorCount() < threshold) {
            return false;
        }
        return previousSeoErrorCount(event) < threshold;
    }

    private int previousSeoErrorCount(SiteUpdatedEvent event) {
        return versionRepository.findBySiteIdAndVersionNumber(event.siteId(), event.versionNumber() - 1)
                .flatMap(prev -> seoReportRepository.findByTaskId(prev.getTaskId()))
                .map(SeoReport::getErrorCount)
                .orElse(0);
    }

    private AutoSite findConfiguredSite(Long siteId) {
        if (siteId == null) {
            return null;
        }
        return siteRepository.findById(siteId)
                .filter(AutoSite::hasNotifyChannelConfigured)
                .orElse(null);
    }

    private void dispatch(AutoSite site, NotificationPayload payload) {
        for (NotifyChannel channel : channels) {
            if (!applies(channel, site)) {
                continue;
            }
            if ("email".equals(channel.name()) && !mailQuotaAllows(site)) {
                continue;
            }
            try {
                if (!channel.send(site, payload)) {
                    log.warn("通知投递未成功：siteId={}，channel={}，type={}",
                            site.getId(), channel.name(), payload.type());
                }
            } catch (Exception e) {
                log.warn("通知投递异常：siteId={}，channel={}，{}",
                        site.getId(), channel.name(), e.getMessage());
            }
        }
    }

    private static boolean applies(NotifyChannel channel, AutoSite site) {
        return switch (channel.name()) {
            case "webhook" -> site.getNotifyWebhookUrl() != null && !site.getNotifyWebhookUrl().isBlank();
            case "email" -> site.getNotifyEmail() != null && !site.getNotifyEmail().isBlank();
            default -> true;
        };
    }

    private boolean mailQuotaAllows(AutoSite site) {
        String today = LocalDate.now().toString();
        mailQuota.keySet().removeIf(key -> !key.endsWith(":" + today));
        String key = site.getId() + ":" + today;
        int sent = mailQuota.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        if (sent > props.getMaxEmailsPerSitePerDay()) {
            log.warn("邮件通知当日配额已用完：siteId={}", site.getId());
            return false;
        }
        return true;
    }
}
```

- [ ] **Step 4: 跑绿** — `mvn -o test -Dtest=NotificationServiceTest` → 全 PASS。
- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/ghgongjin/sitemap/service/notify/NotificationService.java src/test/java/io/github/ghgongjin/sitemap/service/notify/NotificationServiceTest.java
git commit -m "feat: NotificationService 触发矩阵（URL 变化/失败 1·3·10/SEO 边沿）与邮件日配额"
```

---

### Task 10: 读侧 Web——Csv 提取、diff 详情页、diff.csv、版本徽标

**Files:**
- Create: `src/main/java/io/github/ghgongjin/sitemap/service/Csv.java`
- Modify: `src/main/java/io/github/ghgongjin/sitemap/service/SeoReportService.java:405-420`（csvRow 委托）
- Modify: `src/main/java/io/github/ghgongjin/sitemap/controller/AutoSiteController.java`
- Modify: `src/main/resources/templates/auto-detail.html`（版本表 288-316 行区域）
- Create: `src/main/resources/templates/auto-diff.html`
- Modify: `src/main/resources/messages.properties`、`messages_en.properties`（diff 键）
- Test: `src/test/java/io/github/ghgongjin/sitemap/controller/AutoDiffViewTest.java`

**Interfaces:**
- Consumes: `SiteDiffEngine.diff`（T3）、`autoSiteService.version/versions`、控制器 `requireOwned` 404 模式。
- Produces:
  - `Csv.row(StringBuilder csv, Object... cells)`：逐字从 `SeoReportService.csvRow` 迁移（引号加倍 + 公式注入加 `'` 前缀）；`SeoReportService.csvRow` 变一行委托，行为与 `ReportExportIntegrationTest` 不变。
  - `GET /auto/{id}/versions/{v}/diff` → 模板 `auto-diff`，模型 `site/version/view`；`record DiffView(String state, List<String> added, List<String> removed, List<String> changed, int addedCount, int removedCount, int changedCount)`，state ∈ `"FIRST"|"UNAVAILABLE"|"OK"`，明细已截断至 100。
  - `GET /auto/{id}/versions/{v}/diff.csv` → `text/csv;charset=UTF-8`，BOM 开头，表头 `"type","url"`，行 `added|removed|changed`；非 OK 状态 404。
  - 版本表新增「变化」列：v1 → `#{auto.diff.first}` 标签；其余 → `+n −n ~n` 链接到 diff 页（− 用 U+2212）。

- [ ] **Step 1: 写失败测试**

```java
package io.github.ghgongjin.sitemap.controller;

import io.github.ghgongjin.sitemap.entity.UserAccount;
import io.github.ghgongjin.sitemap.repository.AutoSiteRepository;
import io.github.ghgongjin.sitemap.service.AutoSiteService;
import io.github.ghgongjin.sitemap.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @ClassName AutoDiffViewTest
 * @Description 读侧：diff 详情页三组明细/首版/无法比较占位、diff.csv 内容与转义、他人站点与游客 404
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AutoDiffViewTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AutoSiteService autoSiteService;

    @Autowired
    private AutoSiteRepository autoSites;

    @Autowired
    private UserService userService;

    @MockitoBean
    private io.github.ghgongjin.sitemap.service.EnhancedSitemapGeneratorService enhancedService;

    private Long ownerId;

    @BeforeEach
    void loginOwnerAndSeedSite() {
        UserAccount owner = userService.register("diff-owner", "Passw0rd1", "diff-owner@example.invalid");
        ownerId = owner.getId();
    }

    private Long seedTwoVersions() {
        // 真实走 recordSuccess 两次，diff 计数与版本行一致
        io.github.ghgongjin.sitemap.entity.AutoSite site =
                autoSiteService.create(ownerId, "https://diff-test.example.invalid", false, false, false, 24);
        autoSiteService.recordSuccess(site.getId(), "t1",
                "<urlset><url><loc>https://diff-test.example.invalid/a</loc><lastmod>2026-01-01</lastmod>"
                        + "<url><loc>https://diff-test.example.invalid/gone</loc><lastmod>2026-01-01</lastmod></url></urlset>", 2);
        autoSiteService.recordSuccess(site.getId(), "t2",
                "<urlset><url><loc>https://diff-test.example.invalid/a</loc><lastmod>2026-02-02</lastmod>"
                        + "<url><loc>https://diff-test.example.invalid/new</loc><lastmod>2026-02-02</lastmod>"
                        + // 带逗号与公式前缀的 URL 验证 CSV 转义
                        "<url><loc>=https://diff-test.example.invalid/evil,one</loc><lastmod>2026-02-02</lastmod></url></urlset>", 3);
        return site.getId();
    }

    @Test
    void shouldRenderDiffPageWithThreeGroupsForSecondVersion() throws Exception {
        Long id = seedTwoVersions();
        mvc.perform(get("/auto/{id}/versions/2/diff", id).with(user(ownerId.toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("diff-test.example.invalid/new")))
                .andExpect(content().string(containsString("diff-test.example.invalid/gone")))
                .andExpect(content().string(containsString("+2")));
    }

    @Test
    void shouldShowFirstSnapshotLabelOnVersionOne() throws Exception {
        Long id = seedTwoVersions();
        mvc.perform(get("/auto/{id}/versions/1/diff", id).with(user(ownerId.toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("首个快照")));
    }

    @Test
    void shouldExportDiffCsvWithEscapingAndBom() throws Exception {
        Long id = seedTwoVersions();
        String body = mvc.perform(get("/auto/{id}/versions/2/diff.csv", id).with(user(ownerId.toString())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).startsWith("");
        assertThat(body).contains("\"type\",\"url\"");
        assertThat(body).contains("\"added\",\"'https://diff-test.example.invalid/new\"");
        // =前缀公式注入被强制转文本，逗号字段被引号包裹
        assertThat(body).contains("\"added\",\"'=https://diff-test.example.invalid/evil,one\"");
        assertThat(body).contains("\"removed\",\"https://diff-test.example.invalid/gone\"");
        assertThat(body).contains("\"changed\",\"https://diff-test.example.invalid/a\"");
    }

    @Test
    void shouldReturn404ForOtherUsersAndGuestsAndMissingVersion() throws Exception {
        Long id = seedTwoVersions();
        UserAccount other = userService.register("diff-other", "Passw0rd1", "diff-other@example.invalid");
        mvc.perform(get("/auto/{id}/versions/2/diff", id).with(user(other.getId().toString())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/auto/{id}/versions/2/diff.csv", id).with(user(other.getId().toString())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/auto/{id}/versions/2/diff", id))
                .andExpect(status().isUnauthorized());   // 与既有 /auto/{id} 游客口径一致；若现状为 302 跳转登录页则按现状改
        mvc.perform(get("/auto/{id}/versions/99/diff", id).with(user(ownerId.toString())))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldShowChangeBadgesInVersionHistory() throws Exception {
        Long id = seedTwoVersions();
        mvc.perform(get("/auto/{id}", id).with(user(ownerId.toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("+2 −1 ~1")))
                .andExpect(content().string(containsString("首个快照")));
    }
}
```

（`userService.register(...)` 签名以 `UserService` 现状为准，参考 `AuthIntegrationTest` 的注册调用；游客状态断言以 `AutoAccessControlTest` 既有「未登录访问 /auto/{id}」用例的口径替换。`MockMultipartFile` import 若不用请删除。`containsString("+2")` 用 zh 默认 locale，宿主无关。）

- [ ] **Step 2: 跑红** — 编译通过但路由 404 / 断言失败（Csv 未提取时先编译失败，属正常红）。

- [ ] **Step 3a: 提取 Csv**

新建：

```java
package io.github.ghgongjin.sitemap.service;

/**
 * @ClassName Csv
 * @Description CSV 单元格写入：RFC4180 引号转义 + 表格公式注入防护（=+-@ 与前导空白强制加 ' 前缀）
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
public final class Csv {

    private Csv() {
    }

    public static void row(StringBuilder csv, Object... cells) {
        // 逐字迁移自 SeoReportService.csvRow（行为不变，由 ReportExportIntegrationTest 兜底）
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                csv.append(',');
            }
            String value = cells[i] == null ? "" : cells[i].toString();
            String stripped = value.stripLeading();
            if ((!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0)
                    || value.startsWith("\t") || value.startsWith("\r") || value.startsWith("\n")) {
                value = "'" + value;
            }
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }
}
```

`SeoReportService.csvRow` 方法体替换为：

```java
    private void csvRow(StringBuilder csv, Object... cells) {
        Csv.row(csv, cells);
    }
```

- [ ] **Step 3b: 控制器（AutoSiteController 追加）**

```java
    @GetMapping("/{id}/versions/{v}/diff")
    public String diffPage(@PathVariable Long id, @PathVariable("v") int v, Model model) {
        AutoSite site = requireOwned(id, SecurityUtils.currentUserId());
        AutoSiteVersion version = requireVersion(id, v);
        model.addAttribute("site", site);
        model.addAttribute("version", version);
        model.addAttribute("view", diffView(id, version));
        return "auto-diff";
    }

    @GetMapping("/{id}/versions/{v}/diff.csv")
    public ResponseEntity<byte[]> diffCsv(@PathVariable Long id, @PathVariable("v") int v) {
        requireOwned(id, SecurityUtils.currentUserId());
        AutoSiteVersion version = requireVersion(id, v);
        DiffView view = diffView(id, version);
        if (!"OK".equals(view.state())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        StringBuilder csv = new StringBuilder("");
        Csv.row(csv, "type", "url");
        view.added().forEach(url -> Csv.row(csv, "added", url));
        view.removed().forEach(url -> Csv.row(csv, "removed", url));
        view.changed().forEach(url -> Csv.row(csv, "changed", url));
        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDispositionFormData("attachment", "sitemap-diff-v" + v + ".csv");
        headers.setContentLength(body.length);
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private AutoSiteVersion requireVersion(Long id, int v) {
        return autoSiteService.version(id, v)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * 明细按需实时算：v1=FIRST；上一版已被裁剪/XML 缺失/解析异常=UNAVAILABLE；否则 OK（每组截断 100）
     */
    private DiffView diffView(Long siteId, AutoSiteVersion version) {
        if (version.getVersionNumber() == 1) {
            return DiffView.of("FIRST", null);
        }
        AutoSiteVersion previous = autoSiteService.version(siteId, version.getVersionNumber() - 1)
                .orElse(null);
        if (previous == null) {
            return DiffView.of("UNAVAILABLE", null);
        }
        try {
            return DiffView.of("OK", SiteDiffEngine.diff(previous.getSitemapXml(), version.getSitemapXml()));
        } catch (Exception e) {
            log.warn("版本 diff 实时计算失败：siteId={}, v={}, {}", siteId, version.getVersionNumber(), e.getMessage());
            return DiffView.of("UNAVAILABLE", null);
        }
    }

    record DiffView(String state, List<String> added, List<String> removed, List<String> changed,
                    int addedCount, int removedCount, int changedCount) {

        static final int PREVIEW_LIMIT = 100;

        static DiffView of(String state, SiteDiffEngine.SiteDiff diff) {
            if (diff == null) {
                return new DiffView(state, List.of(), List.of(), List.of(), 0, 0, 0);
            }
            return new DiffView(state,
                    diff.added().stream().limit(PREVIEW_LIMIT).toList(),
                    diff.removed().stream().limit(PREVIEW_LIMIT).toList(),
                    diff.changed().stream().limit(PREVIEW_LIMIT).toList(),
                    diff.added().size(), diff.removed().size(), diff.changed().size());
        }

        int overflow() {
            return Math.max(Math.max(addedCount - PREVIEW_LIMIT, removedCount - PREVIEW_LIMIT),
                    changedCount - PREVIEW_LIMIT);
        }
    }
```

需要的 import 追加：`io.github.ghgongjin.sitemap.service.Csv`、`io.github.ghgongjin.sitemap.service.SiteDiffEngine`、`org.springframework.ui.Model` 已有、`MediaType/HttpHeaders/ResponseEntity/StandardCharsets/List` 已有。控制器类内新增嵌套 record 与两辅助方法；`detail()` 方法体追加（Task 11 消费）暂不加。

- [ ] **Step 3c: auto-detail.html 版本表加「变化」列**

表头 `<th th:text="#{auto.th.urls}">URL 数</th>` 之后插入 `<th th:text="#{auto.th.changes}">变化</th>`；行 `</td>`（urlCount 单元格）之后插入：

```html
            <td class="lm diff-badges">
              <span th:if="${v.versionNumber == 1}" class="state state-pending" th:text="#{auto.diff.first}">首个快照</span>
              <a th:unless="${v.versionNumber == 1}" class="link mono"
                 th:href="@{/auto/{id}/versions/{v}/diff(id=${site.id}, v=${v.versionNumber})}"
                 th:text="${'+' + v.diffAdded + ' −' + v.diffRemoved + ' ~' + v.diffChanged}">+0 −0 ~0</a>
            </td>
```

- [ ] **Step 3d: 新建 templates/auto-diff.html**

沿用 auto-detail 头部结构（layout fragments、flash 面板照抄，topbar active='auto'，语言链接参数改为本页路径）；主面板：

```html
<main class="rise">
  <div class="prose-head">
    <span class="tag" th:text="#{auto.diff.tag}">变更差异</span>
    <h2 class="mono url-title" th:text="${site.url}">https://example.com</h2>
    <p class="lede" th:text="#{auto.diff.lede(${version.versionNumber})}">版本对比说明</p>
  </div>

  <div class="panel" th:if="${view.state == 'FIRST'}">
    <p class="note" th:text="#{auto.diff.first}">首个快照</p>
    <div class="actions">
      <a class="btn" th:href="@{/auto/{id}(id=${site.id})}" th:text="#{auto.diff.back}">返回版本历史</a>
    </div>
  </div>

  <div class="panel" th:if="${view.state == 'UNAVAILABLE'}">
    <p class="note" th:text="#{auto.diff.unavailable}">无法比较</p>
    <div class="actions">
      <a class="btn" th:href="@{/auto/{id}(id=${site.id})}" th:text="#{auto.diff.back}">返回版本历史</a>
    </div>
  </div>

  <div th:if="${view.state == 'OK'}">
    <div class="panel">
      <div class="meters">
        <div class="meter"><b th:text="${'+' + view.addedCount}">+0</b><span th:text="#{auto.diff.added}">新增</span></div>
        <div class="meter"><b th:text="${'−' + view.removedCount}">-0</b><span th:text="#{auto.diff.removed}">删除</span></div>
        <div class="meter"><b th:text="${'~' + view.changedCount}">~0</b><span th:text="#{auto.diff.changed}">改动</span></div>
      </div>
      <p class="note" th:if="${view.addedCount + view.removedCount + view.changedCount == 0}" th:text="#{auto.diff.none}">与上一版本没有差异</p>
      <p class="note" th:if="${view.overflow() > 0}" th:text="#{auto.diff.more(${view.overflow()})}">另有 N 条未显示</p>
      <div class="actions">
        <a class="btn primary" th:href="@{/auto/{id}/versions/{v}/diff.csv(id=${site.id}, v=${version.versionNumber})}"
           th:text="#{auto.diff.download}">下载完整 diff CSV</a>
        <a class="btn" th:href="@{/auto/{id}(id=${site.id})}" th:text="#{auto.diff.back}">返回版本历史</a>
      </div>
    </div>

    <div class="list-panel" th:if="${!#lists.isEmpty(view.added)}">
      <div class="list-head"><h3 th:text="#{auto.diff.added}">新增</h3></div>
      <div class="list-scroll"><table class="url-table"><tbody>
        <tr th:each="u : ${view.added}"><td class="lm mono" th:text="${u}">url</td></tr>
      </tbody></table></div>
    </div>
    <div class="list-panel" th:if="${!#lists.isEmpty(view.removed)}">
      <div class="list-head"><h3 th:text="#{auto.diff.removed}">删除</h3></div>
      <div class="list-scroll"><table class="url-table"><tbody>
        <tr th:each="u : ${view.removed}"><td class="lm mono" th:text="${u}">url</td></tr>
      </tbody></table></div>
    </div>
    <div class="list-panel" th:if="${!#lists.isEmpty(view.changed)}">
      <div class="list-head"><h3 th:text="#{auto.diff.changed}">改动</h3></div>
      <div class="list-scroll"><table class="url-table"><tbody>
        <tr th:each="u : ${view.changed}"><td class="lm mono" th:text="${u}">url</td></tr>
      </tbody></table></div>
    </div>
  </div>
</main>
```

（`th:text="${'−' + view.removedCount}"` 的减号为 U+2212 字符，与徽标列一致。）

- [ ] **Step 3e: messages 键（zh，成对补 en，同块位置 auto.push.* 之后）**

```properties
auto.th.changes=变化
auto.diff.first=首个快照
auto.diff.unavailable=无法比较
auto.diff.tag=变更差异
auto.diff.lede=版本 v{0} 与上一版本的 URL 集合差异
auto.diff.added=新增
auto.diff.removed=删除
auto.diff.changed=改动（lastmod 变化）
auto.diff.none=与上一版本没有差异
auto.diff.more=另有 {0} 条未显示，下载完整 CSV 查看全部
auto.diff.download=下载完整 diff CSV
auto.diff.back=返回版本历史
title.autoDiff=Sitemap Studio · 变更差异
```

```properties
auto.th.changes=Changes
auto.diff.first=First snapshot
auto.diff.unavailable=Not comparable
auto.diff.tag=Diff
auto.diff.lede=URL set diff of version v{0} against its previous version
auto.diff.added=Added
auto.diff.removed=Removed
auto.diff.changed=Changed (lastmod)
auto.diff.none=No differences from the previous version
auto.diff.more={0} more not shown — download the full CSV to see all
auto.diff.download=Download full diff CSV
auto.diff.back=Back to version history
title.autoDiff=Sitemap Studio · Diff
```

- [ ] **Step 4: 跑绿 + 导出回归**

Run: `mvn -o test -Dtest=AutoDiffViewTest+ReportExportIntegrationTest` → 全 PASS（证明 Csv 提取无行为漂移）。

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: diff 详情页 + 完整 diff CSV 下载 + 版本历史变化徽标（Csv 转义器提取）"
```

---

### Task 11: 写侧 Web——通知设置保存、打码回显、测试通知（限流）

**Files:**
- Create: `service/notify/NotifySettings.java`、`NotifySettingsView.java`、`NotifySettingsService.java`、`NotifyTestLimiter.java`
- Modify: `controller/AutoSiteController.java`（2 新 POST + detail() 加 notify 模型）
- Modify: `templates/auto-detail.html`（推送面板之后、推送记录之前插入通知面板）
- Modify: messages zh/en（auto.notify.* 键）
- Test: `src/test/java/io/github/ghgongjin/sitemap/controller/NotifySettingsControllerTest.java`

**Interfaces:**
- Consumes: `WebhookUrlPolicy.check`（T6）、`CredentialCipher`（既有）、`NotificationService.test`（T9）、T2 实体字段。
- Produces:

```java
public record NotifySettings(boolean notifyOnChange, boolean notifyOnFailure,
        String webhookUrl, String webhookSecret, String email, int seoErrorThreshold) {}

public record NotifySettingsView(String webhookUrl, boolean hasWebhookSecret, String email,
        boolean notifyOnChange, boolean notifyOnFailure, Integer seoErrorThreshold) {
    static NotifySettingsView of(AutoSite site) {...}  // 有效值展开：null→默认（true/-1）
}

@Service NotifySettingsService {
    AutoSite save(Long id, Long userId, NotifySettings settings);  // 校验：check!=OK→AutoSiteValidationException("auto.notify.err.webhook.<lower>", host 参数)；email 非法→"auto.notify.err.email"；threshold ∉{-1}∪[0,100000]→"auto.notify.err.threshold"；webhookSecret 留空=沿用已存值；email 清空合法（=关邮件）
    Optional<NotifySettingsView> view(Long id);
}

@Component NotifyTestLimiter { boolean allow(Long siteId); }  // 每站滑动窗口 3 次/60s
```

控制器：

```java
    @PostMapping("/{id}/notify/settings")
    public String saveNotifySettings(@PathVariable Long id,
                                     @RequestParam(value = "notifyOnChange", defaultValue = "false") boolean notifyOnChange,
                                     @RequestParam(value = "notifyOnFailure", defaultValue = "false") boolean notifyOnFailure,
                                     @RequestParam(value = "webhookUrl", defaultValue = "") String webhookUrl,
                                     @RequestParam(value = "webhookSecret", defaultValue = "") String webhookSecret,
                                     @RequestParam(value = "email", defaultValue = "") String email,
                                     @RequestParam(value = "seoErrorThreshold", defaultValue = "-1") int seoErrorThreshold,
                                     RedirectAttributes redirect) {
        requireOwned(id, SecurityUtils.currentUserId());
        NotifySettings settings = new NotifySettings(notifyOnChange, notifyOnFailure,
                webhookUrl, webhookSecret, email, seoErrorThreshold);
        return mutate(redirect, "auto.notify.flash.saved", detailPath(id),
                () -> notifySettingsService.save(id, SecurityUtils.currentUserId(), settings));
    }

    @PostMapping("/{id}/notify/test")
    public String testNotify(@PathVariable Long id, RedirectAttributes redirect) {
        requireOwned(id, SecurityUtils.currentUserId());
        if (!notifyTestLimiter.allow(id)) {
            redirect.addFlashAttribute("flashError", "auto.notify.flash.rateLimited");
            return "redirect:" + detailPath(id);
        }
        NotifyOutcome outcome = notificationService.test(id);
        if (outcome.success()) {
            redirect.addFlashAttribute("flash", outcome.messageKey());
        } else {
            redirect.addFlashAttribute("flashError", outcome.messageKey());
        }
        return "redirect:" + detailPath(id);
    }
```

（checkbox 语义：unchecked 不提交 → defaultValue false。表单初值来自 NotifySettingsView 的有效值。`detail()` 中追加 `model.addAttribute("notify", notifySettingsService.view(id).orElse(null));`。`save` 里 userId 二次校验由 `requireOwned(id, SecurityUtils.currentUserId())` 先行 + service 内 `autoSiteService.findOwned(id, userId).orElseThrow(AutoSiteValidationException)` 兜 TOCTOU，沿用既有口径。）

`NotifySettingsService.save` 关键体：

```java
        AutoSite site = autoSiteService.findOwned(id, userId)
                .orElseThrow(() -> new AutoSiteValidationException("auto.error.notFound", String.valueOf(id)));
        String url = settings.webhookUrl() == null || settings.webhookUrl().isBlank()
                ? null : settings.webhookUrl().trim();
        if (url != null) {
            WebhookUrlPolicy.WebhookUrlCheck check = urlPolicy.check(url);
            if (check != WebhookUrlPolicy.WebhookUrlCheck.OK) {
                throw new AutoSiteValidationException(
                        "auto.notify.err.webhook." + check.name().toLowerCase(Locale.ROOT), url);
            }
        }
        String email = settings.email() == null || settings.email().isBlank() ? null : settings.email().trim();

        if (email != null && (email.length() > 254 || !EMAIL_PATTERN.matcher(email).matches())) {
            throw new AutoSiteValidationException("auto.notify.err.email");
        }
        int threshold = settings.seoErrorThreshold();
        if (threshold != -1 && (threshold < 0 || threshold > 100_000)) {
            throw new AutoSiteValidationException("auto.notify.err.threshold");
        }
        site.setNotifyOnChange(settings.notifyOnChange());
        site.setNotifyOnFailure(settings.notifyOnFailure());
        site.setNotifyWebhookUrl(url);
        site.setNotifyEmail(email);
        site.setNotifySeoErrorThreshold(threshold);
        if (settings.webhookSecret() != null && !settings.webhookSecret().isBlank()) {
            site.setNotifyWebhookSecretEnc(cipher.encrypt(settings.webhookSecret().trim()));
        }
        return siteRepository.save(site);
    }

    @Transactional(readOnly = true)
    public Optional<NotifySettingsView> view(Long id) {
        return id == null ? Optional.empty()
                : siteRepository.findById(id).map(NotifySettingsView::of);
    }
}
```

类骨架：`@Service @RequiredArgsConstructor`，字段 `AutoSiteService autoSiteService`（findOwned 复用归属校验与 keyed 异常）、`AutoSiteRepository siteRepository`、`WebhookUrlPolicy urlPolicy`、`CredentialCipher cipher`；常量：

```java
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
```

webhook 校验失败时把用户提交的地址作为占位符参数透出（与 `auto.error.notFound` 的 `id` 参数同口径），见上方 save 体中 `("auto.notify.err.webhook." + ..., url)` 两参写法。

`NotifySettingsView.of`（有效值展开：null→默认，页面上永远显示"生效值"而不是 null）：

```java
    public static NotifySettingsView of(AutoSite site) {
        return new NotifySettingsView(
                site.getNotifyWebhookUrl(),
                site.getNotifyWebhookSecretEnc() != null && !site.getNotifyWebhookSecretEnc().isBlank(),
                site.getNotifyEmail(),
                site.isNotifyOnChangeEffective(),
                site.isNotifyOnFailureEffective(),
                site.getNotifySeoErrorThreshold() == null ? -1 : site.getNotifySeoErrorThreshold());
    }
```

`NotifyTestLimiter`（每站滑动窗口 3 次/60s；键空间=站点数，天然有界）：

```java
@Component
public class NotifyTestLimiter {

    private static final int MAX_PER_WINDOW = 3;
    private static final long WINDOW_MS = 60_000L;

    private final Map<Long, Deque<Long>> hits = new ConcurrentHashMap<>();

    public boolean allow(Long siteId) {
        if (siteId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Deque<Long> queue = hits.computeIfAbsent(siteId, k -> new ArrayDeque<>());
        synchronized (queue) {
            queue.removeIf(t -> now - t >= WINDOW_MS);
            if (queue.size() >= MAX_PER_WINDOW) {
                return false;
            }
            queue.addLast(now);
            return true;
        }
    }
}
```

（以上实现契约块对应 Step 3；先写测试。）

- [ ] **Step 1: 写失败测试**

新建 `src/test/java/io/github/ghgongjin/sitemap/controller/NotifySettingsControllerTest.java`（`@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional`，登录/CSRF/种子站点全部沿用 `AutoAccessControlTest` 的口径）：

```java
package io.github.ghgongjin.sitemap.controller;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.entity.UserAccount;
import io.github.ghgongjin.sitemap.repository.AutoSiteRepository;
import io.github.ghgongjin.sitemap.security.UserAccountDetails;
import io.github.ghgongjin.sitemap.service.UserService;
import io.github.ghgongjin.sitemap.service.notify.NotificationService;
import io.github.ghgongjin.sitemap.service.notify.NotifyOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @ClassName NotifySettingsControllerTest
 * @Description 告警设置写侧：合法保存/secret 留空沿用/三类校验拒绝/越权 404/详情面板渲染/测试通知与限流
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NotifySettingsControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AutoSiteRepository autoSites;

    @Autowired
    private UserService userService;

    /** 只验证控制器接线（flash 键/限流/404），真实投递由 NotificationServiceTest 覆盖 */
    @MockitoBean
    private NotificationService notificationService;

    private UserAccountDetails alice;
    private AutoSite aliceSite;

    @BeforeEach
    void setUp() {
        UserAccount a = userService.register("alice", "Passw0rd1");
        userService.register("bob", "Passw0rd1");
        alice = new UserAccountDetails(a.getId(), a.getUsername(), a.getPasswordHash());
        aliceSite = seedSite(a.getId());
    }

    private AutoSite seedSite(Long userId) {
        AutoSite site = new AutoSite();
        site.setUserId(userId);
        site.setUrl("https://notify-test.example.com");
        site.setIncludeImages(false);
        site.setIncludeVideos(false);
        site.setIncludeNews(false);
        site.setIntervalHours(24);
        site.setEnabled(false);
        site.setNextRunAt(LocalDateTime.now().plusHours(24));
        site.setLastStatus("PENDING");
        site.setCreatedAt(LocalDateTime.now());
        site.setUpdatedAt(LocalDateTime.now());
        return autoSites.saveAndFlush(site);
    }

    @Test
    void shouldSaveSettingsWhenValid() throws Exception {
        mvc.perform(post("/auto/{id}/notify/settings", aliceSite.getId())
                        .with(user(alice)).with(csrf())
                        .param("notifyOnChange", "true")
                        .param("webhookUrl", "https://hooks.example.test/site")
                        .param("webhookSecret", "s3cr3t-key")
                        .param("email", "ops@example.test")
                        .param("seoErrorThreshold", "10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auto/" + aliceSite.getId()))
                .andExpect(flash().attribute("flash", "auto.notify.flash.saved"));

        AutoSite saved = autoSites.findById(aliceSite.getId()).orElseThrow();
        assertThat(saved.getNotifyWebhookUrl()).isEqualTo("https://hooks.example.test/site");
        assertThat(saved.getNotifyEmail()).isEqualTo("ops@example.test");
        assertThat(saved.getNotifySeoErrorThreshold()).isEqualTo(10);
        assertThat(saved.isNotifyOnChangeEffective()).isTrue();
        // checkbox 未勾选 → defaultValue false → 显式关闭（区别于 null 的"沿用默认开"）
        assertThat(saved.isNotifyOnFailureEffective()).isFalse();
        assertThat(saved.getNotifyWebhookSecretEnc()).isNotNull()
                .doesNotContain("s3cr3t-key").startsWith("v1:");
    }

    @Test
    void shouldKeepExistingSecretWhenBlankSubmitted() throws Exception {
        shouldSaveSettingsWhenValid();
        String before = autoSites.findById(aliceSite.getId()).orElseThrow().getNotifyWebhookSecretEnc();

        mvc.perform(post("/auto/{id}/notify/settings", aliceSite.getId())
                        .with(user(alice)).with(csrf())
                        .param("notifyOnChange", "true")
                        .param("webhookUrl", "https://hooks.example.test/site")
                        .param("webhookSecret", "")
                        .param("email", "ops@example.test")
                        .param("seoErrorThreshold", "10"))
                .andExpect(flash().attribute("flash", "auto.notify.flash.saved"));

        assertThat(autoSites.findById(aliceSite.getId()).orElseThrow()
                .getNotifyWebhookSecretEnc()).isEqualTo(before);
    }

    @Test
    void shouldRejectInvalidEmailWithoutSaving() throws Exception {
        mvc.perform(post("/auto/{id}/notify/settings", aliceSite.getId())
                        .with(user(alice)).with(csrf())
                        .param("notifyOnChange", "true")
                        .param("notifyOnFailure", "true")
                        .param("email", "not-an-email"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError", "auto.notify.err.email"));

        assertThat(autoSites.findById(aliceSite.getId()).orElseThrow().getNotifyEmail()).isNull();
    }

    @Test
    void shouldRejectNonHttpWebhookScheme() throws Exception {
        mvc.perform(post("/auto/{id}/notify/settings", aliceSite.getId())
                        .with(user(alice)).with(csrf())
                        .param("notifyOnChange", "true")
                        .param("notifyOnFailure", "true")
                        .param("webhookUrl", "ftp://example.test/hook"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError", "auto.notify.err.webhook.forbidden_scheme"))
                .andExpect(flash().attributeExists("flashErrorArgs"));

        assertThat(autoSites.findById(aliceSite.getId()).orElseThrow().getNotifyWebhookUrl()).isNull();
    }

    @Test
    void shouldRejectBadSeoThreshold() throws Exception {
        mvc.perform(post("/auto/{id}/notify/settings", aliceSite.getId())
                        .with(user(alice)).with(csrf())
                        .param("notifyOnChange", "true")
                        .param("notifyOnFailure", "true")
                        .param("seoErrorThreshold", "-2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError", "auto.notify.err.threshold"));
    }

    @Test
    void shouldReturn404WhenSavingOtherUsersSite() throws Exception {
        UserAccount b = userService.register("carol", "Passw0rd1");
        UserAccountDetails carol = new UserAccountDetails(b.getId(), b.getUsername(), b.getPasswordHash());

        mvc.perform(post("/auto/{id}/notify/settings", aliceSite.getId())
                        .with(user(carol)).with(csrf())
                        .param("notifyOnChange", "true")
                        .param("notifyOnFailure", "true"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRenderNotifyPanelOnDetail() throws Exception {
        String html = mvc.perform(get("/auto/{id}", aliceSite.getId()).with(user(alice)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("notifyForm", "webhookUrl", "seoErrorThreshold");
    }

    @Test
    void shouldFlashTestSentWhenTestSucceeds() throws Exception {
        when(notificationService.test(aliceSite.getId()))
                .thenReturn(new NotifyOutcome(true, "auto.notify.flash.testSent", null));

        mvc.perform(post("/auto/{id}/notify/test", aliceSite.getId())
                        .with(user(alice)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auto/" + aliceSite.getId()))
                .andExpect(flash().attribute("flash", "auto.notify.flash.testSent"));
    }

    @Test
    void shouldRateLimitFourthTestOfTheMinute() throws Exception {
        when(notificationService.test(aliceSite.getId()))
                .thenReturn(new NotifyOutcome(true, "auto.notify.flash.testSent", null));
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/auto/{id}/notify/test", aliceSite.getId())
                            .with(user(alice)).with(csrf()))
                    .andExpect(flash().attribute("flash", "auto.notify.flash.testSent"));
        }
        mvc.perform(post("/auto/{id}/notify/test", aliceSite.getId())
                        .with(user(alice)).with(csrf()))
                .andExpect(flash().attribute("flashError", "auto.notify.flash.rateLimited"));
        verify(notificationService, times(3)).test(aliceSite.getId());
    }

    @Test
    void shouldRedirectGuestFromNotifyEndpoints() throws Exception {
        mvc.perform(post("/auto/{id}/notify/settings", aliceSite.getId()).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/auto/{id}/notify/test", aliceSite.getId()).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }
}
```

另在 `AutoAccessControlTest` 的 `shouldRedirectGuestFromEveryAutoSiteOperation` 的 `@ValueSource` 里追加 `"/auto/1/notify/settings", "/auto/1/notify/test"` 两条（游客门禁清单与端点同步增长）。

- [ ] **Step 2: 跑红**

Run: `mvn -o test -Dtest=NotifySettingsControllerTest` → 编译失败（`NotifySettings` 等类与两个 POST 路由尚不存在）。

- [ ] **Step 3: 实现**

按本任务开头契约逐字落地：`NotifySettings`、`NotifySettingsView`（含 `of`）、`NotifySettingsService`（含 save/view 完整体与 `EMAIL_PATTERN`）、`NotifyTestLimiter`、`AutoSiteController` 两个 POST。控制器类字段追加 4 个（`@RequiredArgsConstructor` 自动进构造器）：

```java
    private final NotifySettingsService notifySettingsService;
    private final NotificationService notificationService;
    private final NotifyTestLimiter notifyTestLimiter;
    private final NotifyProperties notifyProperties;
```

`detail()` 追加：

```java
        model.addAttribute("notify", notifySettingsService.view(id).orElse(null));
        model.addAttribute("notifyPrivateAllowed", notifyProperties.isAllowPrivateNetwork());
```

新增 import：`io.github.ghgongjin.sitemap.config.NotifyProperties`、`io.github.ghgongjin.sitemap.service.notify.{NotificationService, NotifyOutcome, NotifySettings, NotifySettingsService, NotifySettingsView, NotifyTestLimiter}`（`NotifySettingsView` 仅模板经 `notify` 属性消费，控制器 import 里不需要它也可，按 IDE 清理）。

**既有测试适配**（构造器 arity 变化）——`AutoSiteControllerTest.setUp` 第 92 行改为：

```java
        AutoSiteController controller = new AutoSiteController(autoSiteService, pushConfigService,
                sitemapPushService, mock(NotifySettingsService.class), mock(NotificationService.class),
                mock(NotifyTestLimiter.class), new NotifyProperties());
```

（standalone 场景 `NotifySettingsService` 用 mock：`view(...)` 默认返回 `Optional.empty` → 通知面板不渲染，旧断言零扰动；`NotifyProperties` 用真实实例（@Data POJO，默认值即可）。）

- [ ] **Step 4: 通知面板模板 + 文案**

`auto-detail.html`：推送面板（`list-panel push-panel` 的闭合 `</div>`，即 `<p class="push-empty" ...>` 之后）与推送记录面板（`th:if="${!#lists.isEmpty(pushLogs)}"`）之间插入。全部控件走既有类名体系，无任何原生弹框/原生校验：

```html
  <div class="list-panel" th:if="${notify != null}">
    <div class="list-head">
      <h3 th:text="#{auto.notify.title}">变更告警</h3>
      <span class="state"
            th:classappend="${site.hasNotifyChannelConfigured()} ? 'state-success' : 'state-pending'"
            th:text="${site.hasNotifyChannelConfigured()} ? #{auto.notify.on} : #{auto.notify.off}">未启用告警</span>
    </div>
    <form class="push-form" id="notifyForm" method="post" novalidate
          th:action="@{/auto/{id}/notify/settings(id=${site.id})}">
      <div class="push-switches">
        <label class="switch-row">
          <span>
            <b th:text="#{auto.notify.onChange}">URL 变化时通知</b>
            <i th:text="#{auto.notify.onChange.d}">新版本相对上一版有新增/删除/改动时触发</i>
          </span>
          <input type="checkbox" name="notifyOnChange" th:checked="${notify.notifyOnChange}">
          <span class="sw"></span>
        </label>
        <label class="switch-row">
          <span>
            <b th:text="#{auto.notify.onFailure}">连续失败时通知</b>
            <i th:text="#{auto.notify.onFailure.d}">第 1、3、10 次连续更新失败各通知一次</i>
          </span>
          <input type="checkbox" name="notifyOnFailure" th:checked="${notify.notifyOnFailure}">
          <span class="sw"></span>
        </label>
      </div>

      <div class="push-group">
        <h4 class="push-sub" th:text="#{auto.notify.g.channel}">通知渠道</h4>
        <div class="push-grid">
          <div class="field">
            <label class="field-label" for="notifyWebhookUrl" th:text="#{auto.notify.webhookUrl}">Webhook 地址</label>
            <input id="notifyWebhookUrl" type="text" name="webhookUrl" autocomplete="off"
                   th:value="${notify.webhookUrl}" th:placeholder="#{auto.notify.webhookUrl.ph}"
                   placeholder="https://hooks.example.com/sitemap">
            <p class="field-error" id="whUrlErr" hidden>
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 8v5M12 16.5v.01"/></svg>
              <span></span>
            </p>
          </div>
          <div class="field">
            <label class="field-label" for="notifySecret" th:text="#{auto.notify.secret}">签名 Secret</label>
            <input id="notifySecret" type="password" name="webhookSecret" autocomplete="new-password"
                   th:placeholder="${notify.hasWebhookSecret} ? #{auto.notify.secret.keep} : #{auto.notify.secret.hint}"
                   placeholder="可选">
          </div>
          <div class="field">
            <label class="field-label" for="notifyEmail" th:text="#{auto.notify.email}">接收邮箱</label>
            <input id="notifyEmail" type="text" name="email" autocomplete="off" th:value="${notify.email}"
                   th:placeholder="#{auto.notify.email.ph}" placeholder="ops@example.com">
            <p class="field-error" id="emailErr" hidden>
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 8v5M12 16.5v.01"/></svg>
              <span></span>
            </p>
          </div>
          <div class="field">
            <label class="field-label" for="notifyThreshold" th:text="#{auto.notify.seoThreshold}">SEO 错误阈值</label>
            <input id="notifyThreshold" type="text" name="seoErrorThreshold" inputmode="numeric"
                   autocomplete="off" th:value="${notify.seoErrorThreshold}">
            <p class="field-error" id="thresholdErr" hidden>
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 8v5M12 16.5v.01"/></svg>
              <span></span>
            </p>
          </div>
        </div>
        <p class="push-note" th:text="#{auto.notify.threshold.hint}">-1 关闭阈值通知</p>
        <p class="push-note" th:if="${notifyPrivateAllowed}" th:text="#{auto.notify.private.warn}">私网告警提示</p>
      </div>

      <div class="actions push-actions">
        <button class="btn primary" type="submit" th:text="#{auto.notify.save}">保存设置</button>
      </div>
      <p class="note" th:text="#{auto.notify.hint}">测试通知按已保存的设置投递。</p>
    </form>
    <div class="push-actions outside">
      <div class="actions">
        <form method="post" th:action="@{/auto/{id}/notify/test(id=${site.id})}">
          <button class="btn" type="submit" th:text="#{auto.notify.test}">发送测试通知</button>
        </form>
      </div>
    </div>
  </div>
```

页尾再追加一个独立 `<script th:inline="javascript">` 块（**不能**并入 push 的 IIFE——它以 `if (!form) return` 早退）。客户端规则只做即时反馈，服务端校验才是权威：

```html
<script th:inline="javascript">
(function () {
  var form = document.getElementById("notifyForm");
  if (!form) return;
  var msgs = {
    webhook: /*[[#{auto.notify.err.webhookClient}]]*/ 'Webhook 地址需以 http:// 或 https:// 开头',
    email: /*[[#{auto.notify.err.email}]]*/ '邮箱格式不正确',
    threshold: /*[[#{auto.notify.err.threshold}]]*/ 'SEO 错误阈值需为 -1 或 0-100000'
  };
  SitemapUI.formGuard({
    form: form,
    rules: [
      { input: form.querySelector("input[name=webhookUrl]"), error: document.getElementById("whUrlErr"),
        test: function (v) { return v.length === 0 || /^https?:\/\/\S+$/.test(v); }, message: msgs.webhook },
      { input: form.querySelector("input[name=email]"), error: document.getElementById("emailErr"),
        test: function (v) { return v.length === 0 || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v); },
        message: msgs.email },
      { input: form.querySelector("input[name=seoErrorThreshold]"), error: document.getElementById("thresholdErr"),
        test: function (v) { return /^-?\d+$/.test(v) && (v === "-1" || (Number(v) >= 0 && Number(v) <= 100000)); },
        message: msgs.threshold }
    ]
  });
})();
</script>
```

messages.properties（zh，追加在 Task 10 的 `auto.diff.*` 块之后）：

```properties
auto.notify.title=变更告警
auto.notify.on=已启用告警
auto.notify.off=未启用告警
auto.notify.g.channel=通知渠道
auto.notify.webhookUrl=Webhook 地址
auto.notify.webhookUrl.ph=https://hooks.example.com/sitemap
auto.notify.secret=签名 Secret
auto.notify.secret.keep=留空表示沿用已保存的 Secret
auto.notify.secret.hint=可选；填写后请求携带 X-Sitemap-Signature: sha256=HMAC 头
auto.notify.email=接收邮箱
auto.notify.email.ph=ops@example.com
auto.notify.onChange=URL 变化时通知
auto.notify.onChange.d=新版本相对上一版有新增/删除/改动时触发
auto.notify.onFailure=连续失败时通知
auto.notify.onFailure.d=第 1、3、10 次连续更新失败各通知一次
auto.notify.seoThreshold=SEO 错误阈值
auto.notify.threshold.hint=填 -1 表示关闭；本次错误数达到阈值且上一版低于阈值时触发
auto.notify.private.warn=本实例允许指向私网地址的 Webhook（sitemap.notify.allow-private-network 已开启），请只填写自己可信的端点
auto.notify.save=保存设置
auto.notify.test=发送测试通知
auto.notify.hint=测试通知按已保存的设置真实投递；每站点每分钟最多 3 次
auto.notify.flash.saved=告警设置已保存。
auto.notify.flash.testSent=测试通知已投递。
auto.notify.flash.testFailed=测试通知投递失败。
auto.notify.flash.rateLimited=测试通知每站点每分钟最多 3 次，请稍后再试。
auto.notify.err.email=邮箱格式不正确。
auto.notify.err.threshold=SEO 错误阈值需为 -1 或 0-100000 之间的整数。
auto.notify.err.noChannel=请先填写 Webhook 地址或接收邮箱。
auto.notify.err.webhook.malformed=Webhook 地址不合法：{0}
auto.notify.err.webhook.forbidden_scheme=Webhook 仅支持 http/https：{0}
auto.notify.err.webhook.has_credentials=Webhook 地址不允许携带凭据：{0}
auto.notify.err.webhook.dns_failed=Webhook 主机 DNS 解析失败：{0}
auto.notify.err.webhook.denied_always=Webhook 地址解析到内网保留网段，已拒绝：{0}
auto.notify.err.webhook.denied_private=本实例禁止 Webhook 指向私网地址：{0}
auto.notify.err.webhookClient=Webhook 地址需以 http:// 或 https:// 开头
```

messages_en.properties 成对：

```properties
auto.notify.title=Change Alerts
auto.notify.on=Alerts enabled
auto.notify.off=Alerts disabled
auto.notify.g.channel=Channels
auto.notify.webhookUrl=Webhook URL
auto.notify.webhookUrl.ph=https://hooks.example.com/sitemap
auto.notify.secret=Signing Secret
auto.notify.secret.keep=Leave blank to keep the saved secret
auto.notify.secret.hint=Optional; sets X-Sitemap-Signature: sha256=HMAC on each request
auto.notify.email=Notify Email
auto.notify.email.ph=ops@example.com
auto.notify.onChange=Alert on URL changes
auto.notify.onChange.d=Fires when a new version adds, removes or changes URLs
auto.notify.onFailure=Alert on consecutive failures
auto.notify.onFailure.d=One alert each at the 1st, 3rd and 10th consecutive failure
auto.notify.seoThreshold=SEO Error Threshold
auto.notify.threshold.hint=-1 disables it; fires when errors reach the threshold while the previous version was below it
auto.notify.private.warn=This instance accepts private-network webhook targets (sitemap.notify.allow-private-network is on) — only use endpoints you trust
auto.notify.save=Save Settings
auto.notify.test=Send Test Notification
auto.notify.hint=Test notifications really deliver using saved settings; up to 3 per site per minute
auto.notify.flash.saved=Alert settings saved.
auto.notify.flash.testSent=Test notification delivered.
auto.notify.flash.testFailed=Test notification delivery failed.
auto.notify.flash.rateLimited=Test notifications are limited to 3 per site per minute — try again later.
auto.notify.err.email=Invalid email address.
auto.notify.err.threshold=SEO error threshold must be -1 or an integer between 0 and 100000.
auto.notify.err.noChannel=Set a webhook URL or notify email first.
auto.notify.err.webhook.malformed=Invalid webhook URL: {0}
auto.notify.err.webhook.forbidden_scheme=Webhook must be http or https: {0}
auto.notify.err.webhook.has_credentials=Webhook URL must not contain credentials: {0}
auto.notify.err.webhook.dns_failed=DNS lookup failed for webhook host: {0}
auto.notify.err.webhook.denied_always=Webhook resolves to a denied reserved address range: {0}
auto.notify.err.webhook.denied_private=This instance forbids webhooks pointing at private networks: {0}
auto.notify.err.webhookClient=Webhook URL must start with http:// or https://
```

- [ ] **Step 5: 跑绿 + 相关回归**

Run: `mvn -o test -Dtest=NotifySettingsControllerTest+AutoSiteControllerTest+AutoAccessControlTest+NotificationServiceTest` → 全 PASS。

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: 通知设置保存/打码回显/测试通知（每站 3 次/分钟限流）+ 详情页告警面板"
```

---

### Task 12: messages 中英对齐门禁 + 全量双语回归

**Files:**
- Create: `src/test/java/io/github/ghgongjin/sitemap/config/MessagesAlignmentTest.java`

**Interfaces:**
- Consumes: `messages.properties` / `messages_en.properties`（Task 10 起共 13 diff 键 + Task 11 全部 notify 键）。
- Produces: 无生产接口——纯资源门禁，后续任何加键任务都受它约束。

- [ ] **Step 1: 写测试**

```java
package io.github.ghgongjin.sitemap.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName MessagesAlignmentTest
 * @Description i18n 资源门禁：zh 基线与 en 键集合完全一致、值非空、{n} 占位符集合一致（flash 键经 MessageSource 解析，缺一角即页面裸键）
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
class MessagesAlignmentTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)");

    @Test
    void shouldHaveIdenticalKeySets() throws Exception {
        Properties zh = load("messages.properties");
        Properties en = load("messages_en.properties");
        Set<String> zhKeys = keys(zh);
        Set<String> enKeys = keys(en);
        Set<String> onlyZh = new TreeSet<>(zhKeys);
        onlyZh.removeAll(enKeys);
        Set<String> onlyEn = new TreeSet<>(enKeys);
        onlyEn.removeAll(zhKeys);
        assertThat(onlyZh).as("仅存在于中文的键").isEmpty();
        assertThat(onlyEn).as("仅存在于英文的键").isEmpty();
    }

    @Test
    void shouldHaveNoBlankValues() throws Exception {
        for (Properties props : List.of(load("messages.properties"), load("messages_en.properties"))) {
            for (String key : keys(props)) {
                assertThat(props.getProperty(key)).as("键 %s 的值为空", key).isNotBlank();
            }
        }
    }

    @Test
    void shouldAlignPlaceholderIndices() throws Exception {
        Properties zh = load("messages.properties");
        Properties en = load("messages_en.properties");
        for (String key : keys(zh)) {
            assertThat(placeholders(en.getProperty(key))).as("键 %s 占位符不一致", key)
                    .isEqualTo(placeholders(zh.getProperty(key)));
        }
    }

    private Properties load(String name) throws Exception {
        Properties props = new Properties();
        try (InputStream in = new ClassPathResource(name).getInputStream()) {
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return props;
    }

    private Set<String> keys(Properties props) {
        return new TreeSet<>(props.stringPropertyNames());
    }

    private Set<String> placeholders(String value) {
        Set<String> out = new HashSet<>();
        Matcher m = PLACEHOLDER.matcher(value == null ? "" : value);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
```

- [ ] **Step 2: 故障注入验红（证明测试真的有牙齿）**

临时在 `messages_en.properties` 里删除 `auto.diff.none` 一行 →
`mvn -o test -Dtest=MessagesAlignmentTest` 预期 `shouldHaveIdenticalKeySets` FAIL；
再临时把 `auto.notify.err.webhook.malformed`（en）值改成不带 `{0}` → 预期 `shouldAlignPlaceholderIndices` FAIL。
两处还原后重跑 → PASS。

- [ ] **Step 3: 全量回归 ×2 语言环境**

```bash
mvn -o test
mvn -o test -DargLine="-Duser.language=en -Duser.country=US"
```

两者都必须全绿——第二条证明新测试没有偷依赖宿主机 locale（CI 约定；若因 `@MockitoBean` 之类与 argLine 冲突报错，用 `mvn -o test -Dsurefire.argLine=...` 等价变体并在此注明）。

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "test: messages 中英键集/占位符对齐门禁 + 英文宿主机 locale 全量回归"
```

---

### Task 13: 全量验证、冒烟与 PR 更新

**Files:**
- 无新建；产物 = `mvn -o clean verify` 通过记录 + 冒烟记录 + 已推送的 `feature/diff-notification`。

**Interfaces:**
- Consumes: Task 1–12 全部产物。
- Produces: 可合并的 PR #2 增量（评审通过与否由人裁决，**本任务不合并、不打 tag**）。

- [ ] **Step 1: CI 等价门禁**

Run: `mvn -o clean verify`（与 `.github/workflows/ci.yml` 的 `mvn -B verify` 同链路，离线）。
Expected: `BUILD SUCCESS`，0 failures / 0 errors。任何失败都回到对应任务修复后重跑，不允许跳过。

- [ ] **Step 2: jar 冒烟（不依赖走查环境）**

```bash
mvn -o package -DskipTests
java -jar target/sitemap-studio-1.0.0.jar --server.port=18099 \
  --spring.datasource.url="jdbc:h2:mem:smoke;DB_CLOSE_DELAY=-1" &
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:18099/login
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:18099/auto
```

Expected：`/login` → 200；`/auto` → 302（跳登录）。随后杀掉该进程。冒烟的意义：真实装配 `NotificationConfig`/`WebhookSender`/`NotificationService`/（无 spring.mail.host 时）不装配 `MailSender` 的完整上下文能起来。

- [ ] **Step 3: Webhook 真实投递取证（本机闭环，不依赖外网）**

```bash
node -e "require('http').createServer((req,res)=>{let b='';req.on('data',c=>b+=c);req.on('end',()=>{console.log(req.method,req.url,'sig='+req.headers['x-sitemap-signature']);console.log(b);res.writeHead(200);res.end('ok')})}).listen(19999)" &
```

再启 Step 2 的 jar（同命令），注册临时用户 → 添加自动更新站点 → 详情页填 Webhook `http://127.0.0.1:19999/hook`（默认 `allow-private-network=true` 才会接受，这本身就是配置生效的证据）→ 保存 → 点「发送测试通知」。
Expected：node 端打印 `POST /hook sig=sha256=<64hex>` 与 JSON 载荷（含 `"type":"TEST"`）。填了 Secret 时 sig 必须存在且以 `sha256=` 开头；不填时无该头。
**8091 走查环境如需重建复用浏览器全链路走查（徽标/diff 页/CSV 渲染），先征求用户同意，不在本计划内擅自重启。**

- [ ] **Step 4: 推送更新 PR #2**

```bash
git push origin feature/diff-notification
"/c/Program Files/GitHub CLI/gh.exe" pr view 2 --repo gh-gongjin/sitemap-studio --json state,commits --jq '{state, count: (.commits|length)}'
```

Expected：PR #2 仍 OPEN 且 commits 数增长。CI 在 PR 上跑 `mvn -B verify`：`"/c/Program Files/GitHub CLI/gh.exe" pr checks 2 --repo gh-gongjin/sitemap-studio --watch`（网络不稳时按既有重试口径处理；命令行直连 github.com 偶发失败不代表代码问题，以本地 Step 1 为权威）。

- [ ] **Step 5: 收尾记录**

- 更新项目记忆 `project-feature-roadmap-v2.md`：功能 A 实施完成度、遗留事项（如 CI 绿与否）。
- PR 描述追加验证摘要（测试总数、冒烟证据），交用户评审合并；合并后如需 v1.1.0 发布，另走"先问再打 tag"流程。

---

## 验收对照（spec → task 映射）

| Spec 要求 | 落在 |
|---|---|
| 版本间 +/−/~ 计数入库、首版 0/0/0 永不通知 | T2 T3 T4 T5 T9 |
| diff 明细页（每组预览 100 + 完整 CSV 下载 + BOM/公式注入防护） | T10 |
| 版本历史「变化」徽标（U+2212）与链接 | T10 |
| Webhook（HMAC 签名/超时/1 次重试/独立 SSRF 策略，红线不削弱） | T6 T7 |
| 邮件（i18n 无关固定双语、Thymeleaf 模板、无 mail.host 不装配） | T1 T8 |
| 触发矩阵：URL 变化 / 失败 1·3·10 / SEO 边沿（独立于 onChange 开关） | T9 |
| 通知失败吞掉记 WARN、异步隔离、队列满丢最旧 | T7 T9 |
| 每站邮件日配额 50、测试通知 3 次/分钟 | T9 T11 |
| 设置打码回显（secret 留空沿用）、校验键控 i18n | T11 |
| 全局开关 sitemap.notify.enabled | T1 T9 |
| 中英文案成对 + 资源束门禁 | T10 T11 T12 |
| 离线构建、locale 无关测试、SecurityConfig 不动 | Global Constraints + T12 T13 |
