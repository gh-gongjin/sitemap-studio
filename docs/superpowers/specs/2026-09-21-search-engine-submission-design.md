# 搜索引擎提交（功能 C）设计文档

**日期：** 2026-09-21
**状态：** 已与用户逐节确认
**范围：** sitemap-studio 功能 C —— 在既有 IndexNow 之上新增百度主动推送与 Google Search Console sitemap 提交两个通道

## 1. 背景与目标

自动更新站点在 SFTP/FTP 推送成功后已能通过 IndexNow 通知 Bing/Yandex/Naver 等 10+ 引擎（`IndexNowClient`，v1.0.x 已落地）。缺失的是两个大市场：

- **百度**：中国市场必须走百度搜索资源平台"普通收录"主动推送 API（`data.zz.baidu.com/urls`），IndexNow 不覆盖百度。
- **Google**：旧的 sitemap ping 端点已于 2024 年初被官方移除；剩余正路是 Search Console API 的 `sitemaps.submit`（服务账号凭据，一次配置长期有效）。

Bing 独立提交 API 官方已引导放弃（Bing 本身是 IndexNow 发起方），不做；360/神马/搜狗接口公开度与稳定性差，不做。

**目标**：站点地图版本更新并成功上传服务器后，自动"捎话"给百度与 Google；失败可在详情页手动重发；全程不影响既有推送与更新结果。

## 2. 用户裁定记录（brainstorming 逐问确认）

| 决策点 | 裁定 |
|---|---|
| 通道范围 | 百度主动推送 + GSC API（在已有 IndexNow 之上），不做 Bing WMT / 国产全家桶 |
| 触发时机 | 自动更新推送成功后顺带提交 + 详情页「立即提交」手动按钮；不做独立调度器 |
| 百度提交内容 | 只提本版相对上版 diff 的**新增+修改** URL（首版=全量），截断 2000 条/次；不做每次全量 |
| 架构 | 方案 1：扩展 `push_config` + 独立 `service/submission` 包；不建独立 SubmissionConfig 表，也不迁移 IndexNow |

## 3. 总体架构与数据流

```
AutoSiteUpdater.update(site)
  ├─ 抓取 → recordSuccess(新版本) → SEO 报告 → 通知事件          （现有，不动）
  ├─ sitemapPushService.push(siteId)   SFTP 上传 + IndexNow      （现有，不动）
  └─ submissionService.submitAfterPush(siteId)                   （新增，失败只记日志）
        ├─ 百度通道（启用时）：SiteDiffEngine 取 added+changed（首版/上版缺失=全量）
        │    → 过滤外域 URL → 截断 2000 → BaiduPushClient.push(site, token, urls)
        │    → 解析 {success|remain|error} → 写 SubmissionLog
        └─ GSC 通道（启用时）：GoogleSitemapClient
             解密服务账号 JSON → 自签 JWT → OAuth2 换 token
             → PUT searchconsole sitemaps.submit → 写 SubmissionLog

POST /auto/{id}/submission/run（手动「立即提交」）→ 同一 submitAfterPush 入口
```

**零新增 Maven 依赖**：GSC 授权走 JWT-bearer 断言流（RFC 7523），私钥用 JDK `java.security`（PKCS#8 解析 + `Signature("SHA256withRSA")`）自签，不引 Google SDK；其余为普通 HTTP，复用 `RestClient`。

### 组件边界

| 类（`service/submission` 包） | 单一职责 | 依赖 |
|---|---|---|
| `BaiduPushClient` | 百度接口编解码 + 响应/错误码语义化 | RestClient |
| `GoogleSitemapClient` | 服务账号 JSON → JWT → token → submit 完整链路 | RestClient + JDK 密码学 |
| `SearchEngineSubmissionService` | 读配置、选 diff、过滤截断、调通道、记日志、隔离异常 | 上两者 + `PushConfigRepository` + `AutoSiteService`（版本 XML） |

客户端不感知数据库与 diff，可独立单测；编排层不碰 HTTP。IndexNow 保持现状（它的 key 文件发布依赖 SFTP 传输通道，迁入本子系统反而制造耦合）。

## 4. 数据模型

### 4.1 `push_config` 加 8 列

| 列 | 类型 | 约束 |
|---|---|---|
| `baidu_enabled` | boolean | NOT NULL，**`@ColumnDefault("false")`** |
| `baidu_site` | varchar(512) | 可空 |
| `baidu_token_enc` | varchar(4096) | 可空，CredentialCipher AES-256-GCM |
| `gsc_enabled` | boolean | NOT NULL，**`@ColumnDefault("false")`** |
| `gsc_site_url` | varchar(512) | 可空（GSC 已验证 property） |
| `gsc_sitemap_url` | varchar(1024) | 可空（公网可访问完整地址） |
| `gsc_service_account_json_enc` | varchar(24576) | 可空（整份 JSON 加密存储） |
| `gsc_client_email` | varchar(256) | 可空，**明文**（非秘密：服务账号邮箱本就对 GSC 管理员可见）；保存时从已验证 JSON 提取 |

**DDL 红线（v1.1.1 `skipped_pages` 500 教训）**：给存量表加 NOT NULL 列必须带库级默认值；配 schema 守卫测试（INFORMATION_SCHEMA COLUMN_DEFAULT 断言，照 `SeoReportSchemaDefaultsTest` 模式），保证旧库重启不炸。

GSC 的 property 与 sitemap 地址是两个独立输入不能互推（`sc-domain:` 资源、跨子域声明等场景），故分列存储。

### 4.2 新表 `submission_log`

`id, site_id, version_number（可空）, channel(BAIDU|GSC), status(SUCCESS|FAILED), error_code（可空）, detail(varchar 512，人话摘要), duration_ms, created_at`。每站保留最近 50 条，超出裁剪（与 `push_log` 同款策略）。

detail 示例：`接收 23，超限 5，剩余配额 120` / `GSC 已受理` / `服务账号未加入站点用户（403），请到 GSC「设置→用户和权限」添加 xxx@yyy.iam.gserviceaccount.com`。

### 4.3 校验规则（保存即校验，fail fast）

- `baidu_site`：http(s)，且仅 `scheme://host`（无路径、无查询）；`baidu_token`：`[A-Za-z0-9_-]{8,64}`。
- GSC JSON：可解析、`type=="service_account"`、`client_email`/`private_key`/`token_uri` 齐备、私钥能被 JDK 解析为 PKCS#8 RSA。坏 JSON 在入口拒绝，不留到每次提交静默失败。
- `gsc_site_url`：`sc-domain:` 前缀 或 http(s) URL 两种形态之一。
- `gsc_sitemap_url`：绝对 http(s) URL。（Google 抓的是这个地址，本工具不代抓，无 SSRF 面。）
- **凭据留空 = 沿用已存值**（同 SFTP 密码语义）。
- 视图 `SubmissionView` 脱敏：只回显布尔 / baidu_site / property / sitemap URL / **明文列 `gsc_client_email`**；token 与 JSON 明文永不出库到页面（client_email 走独立列而非现场解密 JSON，避免解密失败拖垮详情页）。

### 4.4 保存入口

新 record `SubmissionSettings` + `PushConfigService.saveSubmission(siteId, settings)` + 独立 POST 端点 `POST /auto/{id}/submission/settings`。不把 7 个字段堆进已 12 参数的 `PushSettings`，现有推送表单及其测试零扰动。

## 5. 提交流程细节

### 5.1 百度

- `POST https://data.zz.baidu.com/urls?site={site}&token={token}`，`Content-Type: text/plain`，body 一行一 URL。
- URL 预过滤：仅保留 host 与 `baidu_site` 一致且为 http(s) 的 URL（外域必被拒、纯烧配额），过滤数量写进 detail。
- 截断 2000 条/次（请求体上限留余量）。
- 响应两态：`{"success":N,"remain":M}`（N=实际接收数，M=当日剩余配额）或 `{"error":code,"message":…}`；常见错误码（配额为 0、域名未绑定、token 无效）翻译成人话入日志。

### 5.2 GSC

- JWT claims：`iss`=client_email、`scope`=`https://www.googleapis.com/auth/indexing`、`aud`=token_uri、`exp`/`iat` 现取；RS256 签名。
- `POST token_uri`，`grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion={jwt}`，按 `expires_in` 使用 token（**不做缓存**，低频动作省状态）。
- `PUT https://searchconsole.googleapis.com/webmasters/v3/sites/{urlEncode(property)}/sitemaps/{urlEncode(sitemapUrl)}`，空 body，2xx 即受理。
- HTTP 403 单独分类为"服务账号未加入站点用户"，detail 直接给出要添加的邮箱。

### 5.3 共同规则

- **每通道只试一次，不自动重试**：推送重试因上传必须成；提交是"捎话"，下版还会再报，或用户手动重发。
- 一切异常止步于 `SubmissionLog`；编排层返回聚合 `SubmissionOutcome`（各通道状态 + 摘要）。**提交失败绝不改站点状态、绝不影响推送结果**（与 IndexNow 同待遇），`AutoSiteUpdater` 整段 try-catch。
- 超时：连接/读取各 10s。
- 上一版本 XML 已被裁剪/缺失 → 百度通道退化为全量（截断 2000），不视为错误。
- 凭据解密失败 → 该通道 FAILED +「凭据无法解密，请重新保存设置」，另一通道照常执行。

## 6. 安全

- 出网白名单：仅 `data.zz.baidu.com`、`oauth2.googleapis.com`（及 JSON 中 `token_uri` 同域校验）、`searchconsole.googleapis.com` 三个官方端点。**不接受用户填任意提交地址**。
- `CrawlUrlPolicy` SSRF 红线不动；本子系统与其无交集（提交目标是固定域名，不是用户提供的 URL）。
- 凭据一律 CredentialCipher 加密落库，页面永不回显明文；GSC JSON 含私钥，`toString` 排除（沿用 `passwordEnc` 惯例）。
- 测试端点覆写：`sitemap.submission.baidu-endpoint`、`sitemap.submission.gsc-base-url` 两个配置属性，默认官方值，仅供集成测试指向 WireMock。**覆写入口是 application.properties/环境变量（启动级），不是用户表单**，运行期用户无法改写。
- 全部提交/日志端点走既有登录作用域：`requireOwned`，他人站点一律 404。

## 7. UI（`auto-detail` 页新区块"搜索引擎提交"）

- 延续推送设置区块的自研系统风格控件（禁原生 confirm/校验气泡/下拉），文案 zh/en 双语（messages 文件，key 前缀 `auto.submission.*`）。
- 百度行：开关 + site + token；GSC 行：开关 + property + sitemap 公开 URL + 服务账号 JSON 粘贴框，保存成功后只读回显 `client_email`。
- 按钮：「保存设置」「立即提交」；提交历史表（通道/状态/详情/时间）与推送日志同款样式。
- 指引文案：百度 token 在资源平台"普通收录"页获取；GSC 需创建服务账号并把其邮箱加入站点属性的"用户和权限"（403 头号原因，提前讲清）。
- 「立即提交」不做限流（百度配额天然限死、GSC 幂等、手动低频）。

## 8. 测试策略

| 层 | 内容 |
|---|---|
| 客户端单测 | `MockRestServiceServer` 断言：百度 text/plain 换行 body 与响应/错误码翻译；GSC JWT-bearer 授权流形态、property/sitemap 路径编码、RS256 签名可验（测试现场生成 RSA 密钥对拼 JSON） |
| 配置校验 | `saveSubmission` 矩阵：坏 JSON/缺字段/token 格式/留空沿用/视图脱敏（token、JSON 绝不出现在视图） |
| 编排单测 | Mockito 客户端：外域过滤、2000 截断、首版全量、上版缺失退化、单通道失败不断另一通道、异常永不外溢、日志裁剪 50 |
| schema 守卫 | 旧库升级模拟：新 boolean 列 COLUMN_DEFAULT 断言 |
| Controller | MockMvc：归属 404、保存校验 flash、立即提交流转 |
| 全量 | `mvn -o clean verify`：基线 539 通过/0 失败/7 跳过 之上全绿；新测试不依赖宿主机 locale |
| 冒烟/走查 | jar 冒烟 + VisualHarnessTest（8091）人工走查；真实 API 端到端以用户自有百度 token 验证（GSC 视用户账号条件） |

## 9. 明确不做（YAGNI 裁定）

- Bing WMT 独立提交 API（官方引导放弃，IndexNow 已覆盖 Bing）
- 360/神马/搜狗等国产通道（文档与稳定性差）
- Google Indexing API 逐条 URL 提交（仅适用 JS 渲染页，语义不符）
- OAuth 用户授权跳转流（本地部署工具做 redirect dance 不现实，服务账号一次配置永久有效）
- GSC token 缓存、提交自动重试、提交独立调度器、手动按钮限流
- IndexNow 迁入本子系统

## 10. 全局约束（实施计划逐字继承）

- Spring Boot 3.5.16 / Java 21 / 包根 `io.github.ghgongjin.sitemap`；**零新增 Maven 依赖**
- H2 + ddl-auto=update；存量表加 NOT NULL 列必须带 `@ColumnDefault`
- 离线构建 `mvn -o`；测试基线 539 通过/0 失败/7 跳过，只增不减
- 双语 i18n；系统风格自研控件；`requireOwned` 用户隔离；`CrawlUrlPolicy` 不动
- git 提交身份用仓库级 noreply；PR → CI 绿 → squash 合并（标题带 `(#N)`）惯例
