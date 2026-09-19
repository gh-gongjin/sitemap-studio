# 变更差异与告警（Diff & Notification）设计文档

日期：2026-09-19　状态：已与用户分节确认　负责：代管（gh-gongjin/sitemap-studio）

## 背景与目标

功能路线图 v2 的第一项（A：变更 diff + 告警，排在 C 搜索引擎提交、D API+Docker+质量 之前）。
自动更新站点（AutoSite）已具备定时重爬、版本历史（完整 XML 落库）与 SFTP/IndexNow 推送，
但用户无法回答"这次更新到底动了什么"，站点长期挂掉也无任何主动提醒。本功能补齐：

1. 版本间 URL 集合差异的展示与导出（+新增 / −删除 / ~改动）
2. 三类事件的主动通知：URL 变化、连续爬取失败、SEO 错误数跨阈值
3. 通知通道：Webhook（零配置即用）+ 邮件（全局 SMTP）

非目标（本期不做）：diff 明细落库与趋势统计、站内消息中心、通知投递记录/重发队列、
按任意两版本互比（列表只提供相邻版本 diff）、Slack/钉钉专用格式（用通用 webhook JSON 代替）。

## 需求裁定（用户确认记录）

| 决策点 | 结论 |
|---|---|
| 通知通道 | B：webhook + 邮件双通道 |
| 触发条件 | 三件套：URL 变化非空 / 连续失败第 1、3、10 次 / SEO error 跨阈值 |
| diff 展示 | 列表计数摘要 + 版本详情三组明细（各截断 100）+ 完整 diff CSV 下载 |
| SMTP 归属 | 全局 SMTP（部署者配置），用户界面只填收件人与开关；未配置则邮件通道自动隐身 |
| 实施方案 | 方案 1：写入时算 diff 存计数、明细按需实时算、事件驱动异步通知 |

## 架构与数据流

```
AutoSiteUpdater（既有，定时/手动重爬）
  ├─ 成功 → 保存 AutoSiteVersion（既有）
  │    ├─ SiteDiffEngine.diff(上一版 XML, 新版 XML) → SiteDiff{added, removed, changed}
  │    ├─ 版本行写入 3 个计数列（明细不落库）
  │    └─ 发布 SiteUpdatedEvent(siteId, versionId, 计数, seoErrorCount, firstVersion)
  └─ 失败 → consecutiveFailures += 1，命中 {1,3,10} 时发布 SiteFailedEvent；成功时清零

NotificationService（新，@EventListener + @Async("notifyExecutor") 2 线程 / 队列 100）
  ├─ 读站点通知配置，判定触发条件（含边沿触发规则）
  ├─ 组装 NotificationPayload（统一载荷，与通道无关）
  └─ 分发 WebhookSender / MailSender（各自吞异常记日志，互不影响）
```

### 组件契约

| 单元（新） | 职责 | 接口 |
|---|---|---|
| `SiteDiffEngine` | 纯函数：两版 sitemap XML → `SiteDiff(added, removed, changed)`（`List<String>` 明细 + 计数） | `diff(String xmlOld, String xmlNew)`；复用 `SitemapEntryParser` |
| `SiteUpdatedEvent` / `SiteFailedEvent` | 不可变 record 事件 | Spring 事件 |
| `NotificationService` | 唯一监听器；触发判定与载荷组装；暴露 `test(siteId)` | 监听事件 |
| `NotifyChannel`（接口）+ `WebhookSender` / `MailSender` | 单一投递职责 | `send(AutoSite, NotificationPayload) -> boolean` |

`WebhookSender`：POST JSON（Jackson 序列化），头 `X-Sitemap-Signature: sha256=<HMAC(body, secret)>`
（secret 未配置则省略头），超时 `sitemap.notify.webhook-timeout-ms`，失败间隔 2s 重试 1 次。
`MailSender`：`spring-boot-starter-mail` + Thymeleaf 邮件模板（text + html multipart），
仅当 `spring.mail.host` 存在时经 `@ConditionalOnProperty` 装配。

### 实体扩展（`ddl-auto=update` 自动建列，无迁移脚本）

`AutoSiteVersion`：`diffAdded` / `diffRemoved` / `diffChanged`（int not null default 0）。
`AutoSite`：`consecutiveFailures`(int)、`notifyWebhookUrl`、`notifyWebhookSecret`（`CredentialCipher`
AES-256-GCM 加密存储，回显打码）、`notifyEmail`、`notifyOnChange`(bool，默认 true)、
`notifyOnFailure`(bool，默认 true)、`notifySeoErrorThreshold`(int，默认 -1 = 关闭)。

### diff 语义

- added / removed：按 loc 精确匹配的集合差。
- changed：同 loc 且 lastmod 字符串不同（不做时区归一化；仅 priority/changefreq 变化不算 changed）。
- 首版：计数存 0/0/0，UI 显示"首个快照 / First snapshot"，不触发任何通知。

## Web 界面（延续 SitemapUI 组件与双语规范）

- `/auto/{id}` 版本历史列表：每行 "+n −n ~n" 摘要徽标。
- 版本详情：三组 URL 明细各截断 100 条；"下载完整 diff CSV" → `GET /auto/{id}/versions/{v}/diff.csv`
  （列：type,url；CSV 转义沿用报告导出既有转义器）。
- 站点编辑表单新增"通知"区：webhook URL + secret、收件邮箱、两个开关、SEO 阈值、
  内网 webhook 警示文案、"发送测试通知"按钮（`POST /auto/{id}/notify/test`）。
- 全部新端点走既有"归属校验失败 → 404"模式；测试通知端点内存令牌桶限流（每站每分钟 3 次）。

## 配置与安全

```
spring.mail.*                                # 缺省即邮件通道不装配
sitemap.notify.enabled=true                  # 全局一键关停
sitemap.notify.webhook-timeout-ms=10000
sitemap.notify.allow-private-network=true    # webhook 内网/回环放行开关（默认放行，见下）
sitemap.notify.max-emails-per-site-per-day=50
```

1. `WebhookUrlPolicy`（新，独立于 `CrawlUrlPolicy`，后者零改动）：强制 http/https；
   云元数据/链路本地地址段（169.254.0.0/16 等）无条件拒绝；私有网段由
   `allow-private-network` 控制（默认 true——自托管挂本机接收端是主场景，UI 有警示）。
   保存时与发送时都做解析后 IP 校验。
2. webhook secret 复用 `CredentialCipher` 加密；密钥文件机制（`./data/push.key`）不变。
3. 通知载荷中来自爬取结果的 URL：CSV 走既有转义、HTML 走 Thymeleaf 转义、JSON 走 Jackson，
   无手工字符串拼接。
4. SMTP 凭据仅存在于部署者环境变量，永不入库、永不回显。
5. 新文案 zh 基线 + `messages_en` 全量键对齐（约 40 键）。

## 错误处理与边界

| 场景 | 行为 |
|---|---|
| webhook 超时/非 2xx | 异步池内重试 1 次，仍失败 WARN；无投递记录表，用户用"测试通知"自查 |
| 邮件异常/超每日配额 | WARN；配额超限当日只走 webhook |
| 通知侧任何异常 | 与主流程完全隔离，绝不导致爬取/存版本/推送回滚；线程池满丢弃最旧 + WARN |
| 历史 XML 损坏致 diff 抛异常 | 版本照常保存、计数 0/0/0、WARN；详情页显示"无法比较"占位 |
| 站点无任何通知配置 | 事件到达即短路返回 |
| 长期连续失败 | 仅第 1、3、10 次提醒，之后静默 |
| SEO 阈值 | 边沿触发：本次 ≥ 阈值且上一版 < 阈值才发 |
| 首版 | 不通知 |

## 测试策略（TDD，先红后绿）

1. `SiteDiffEngine`：表驱动纯函数单测——增删改组合、重复 loc、空 XML、lastmod 缺失、
   编码/大小写边界、1000 URL 性能冒烟。
2. `NotificationService`：触发矩阵（三条件 × 开关 × 边沿规则），Mockito 验证事件→载荷→通道分发。
3. `WebhookUrlPolicy`：元数据段拒绝、内网开关两态、非 http scheme、DNS 解析失败。
4. MockMvc：diff.csv 端点（内容/转义/越权 404）、版本列表徽标渲染、测试通知端点（限流、越权）、
   通知设置保存（secret 打码回显、加密落库）。
5. 双 locale 回归：新增断言不依赖宿主机语言（CI 约定），本地以
   `-DargLine="-Duser.language=en -Duser.country=US"` 复跑。
6. harness 走查：播种"存在 diff 的第二版本 + 配好 webhook 的站点"，8091 浏览器截图验证中英双语界面。

## 发布

随功能 PR 合并后计入下一版本（v1.1.0，用户拍板节奏），发布走既有 tag → release.yml 流程。
