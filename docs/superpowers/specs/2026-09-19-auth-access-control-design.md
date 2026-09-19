# 登录注册与功能门禁 — 设计文档

日期：2026-09-19
状态：已与用户逐节确认

## 1. 目标与范围

为 Sitemap Studio 增加账号体系：**注册 / 登录 / 退出**。在此基础上对功能分级：

- **游客免费**：首页输入网址 → 爬取 → 预览 → 下载 XML 站点地图（保持首页"免费 · 免注册"定位）。
- **登录可用**：SEO 报告查看（`/reports`、`/report/{id}`）、报告导出（CSV/PDF/Word）、自动更新（`/auto/**` 全部功能，含推送配置）。
- **数据按用户隔离**：报告与自动更新站点归属于登录用户，每人只能看到、操作自己的数据；登录用户爬取产生的报告自动绑定其账号。

不做（YAGNI）：角色/权限体系、邮箱验证、找回密码、验证码、第三方登录、管理员后台。

## 2. 技术选型

- `spring-boot-starter-security`（Spring Boot 3.5.16 管理版本），表单登录 + BCrypt + CSRF，会话存 `JSESSIONID`（HttpOnly，容器默认）。
- 用户表走现有 JPA + H2（`ddl-auto=update` 自动建表建列），不引入 Flyway（与项目现状一致）。
- 测试引入 `spring-security-test`（test scope）。
- 不新增其他运行时依赖，不依赖系统软件。

## 3. 访问范围矩阵

| 路径 | 匿名 | 已登录 |
|------|------|--------|
| `GET /`、`POST /generate`、`POST /generate-async`、`GET /task/{id}`、`GET /api/task/{id}/**`、`GET /preview`、`GET /download` | ✅ | ✅ |
| `GET /about`、`GET /help`、静态资源（css/js/fonts）、WebSocket（`/ws/**`） | ✅ | ✅ |
| `GET/POST /login`、`GET/POST /register` | ✅（已登录访问则 302 回首页） | — |
| `GET /reports` | ✅ 页面直接打开，空列表 + 登录/注册引导 | ✅ 仅本人报告 |
| `GET /report/{taskId}`、`GET /report/{taskId}/export` | ❌ 302 → `/login`，登录后回跳 | ✅ 仅本人报告，非本人 404 |
| `GET /auto` | ✅ 页面直接打开，空列表 + 登录/注册引导 | ✅ 仅本人站点 |
| `POST /auto`、`/auto/{id}/**`（详情/下载/全部写操作） | ❌ 302 → `/login`，登录后回跳 | ✅ 仅本人站点，非本人 404 |

> 2026-09-19 修订（用户反馈）：列表页（`/reports`、`GET /auto`）不再点开即跳登录，游客可直接浏览空态页并看到登录引导；只有真正的使用动作——查看/导出报告、添加与操作自动更新站点——才被门禁 302 到登录页，`SavedRequest` 登录后回跳原目标。

## 4. 数据模型

### 4.1 新表 `user_account`

| 列 | 类型 | 约束 |
|----|------|------|
| `id` | bigint | PK，自增 |
| `username` | varchar(20) | 唯一；3–20 位，仅字母/数字/下划线 |
| `password_hash` | varchar(60) | BCrypt 输出 |
| `created_at` | timestamp | 注册时刻 |

### 4.2 现有表变更

- `seo_report` 增加可空列 `user_id`（bigint）。
- `auto_site` 增加可空列 `user_id`（bigint）。
- `push_config`、`push_log`、`auto_site_version` 不加列——经由所属 `auto_site.user_id` 间接隔离。

### 4.3 存量数据处理

上线前产生的行 `user_id = null`，**不回填、不删除**：不出现在任何用户的列表；直接按旧 taskId/id 访问详情或导出返回 404。演示库可清空则清空。

## 5. 组件设计

```
config/SecurityConfig        过滤器链、路径授权矩阵、表单登录/退出、自定义 AuthenticationEntryPoint(302→/login)
entity/UserAccount           用户实体
repository/UserAccountRepository   findByUsername / existsByUsername
service/UserService          注册（格式校验→查重→BCrypt→入库）、按 id 查用户名（顶栏展示）
controller/AuthController    GET /login、GET /register、POST /register（成功后 AuthenticationFilter 式手动登录并回跳）
security/SecurityUtils       静态读取当前登录用户 id（未登录返回 null）
templates/login.html         登录页（复用 layout fragment，暗色风格）
templates/register.html      注册页（同上）
fragments/layout.html        顶栏改造：匿名→"登录 / 注册"；已登录→用户名 + "退出"
```

改造点：

- `SeoReportService.save(taskId, siteUrl)` → `save(taskId, siteUrl, Long userId)`。
  - `SitemapController` `/generate-async`（约 149 行）：**提交任务时在请求线程捕获** `SecurityUtils.currentUserId()`，作为 final 变量传入异步 lambda（异步线程无 SecurityContext）。
  - `AutoSiteUpdater`（约 71 行）：从 `AutoSite.userId` 取值传入。
- `SeoReportRepository` 增加 `findByTaskIdAndUserId`、`findByUserIdOrderByCreatedAtDesc`（列表按用户过滤，上限仍 20 条）。
- `AutoSiteRepository` / `AutoSiteService` 全部查询与变更方法带 `userId`；`AutoSiteController` 从会话取当前用户，越权 id 一律 404。
- 报告导出 `/report/{id}/export` 走同一归属校验（先按 `taskId+userId` 查到报告再生成文件）。

## 6. 关键流程

### 6.1 注册

表单 POST `/register`（带 CSRF 令牌）→ `UserService` 校验（用户名格式、密码 ≥8 位含字母和数字、两次输入一致、用户名未占用）→ 入库 → 302 到 `/login?registered=1`，登录页内联提示"注册成功，请登录"，由用户完成登录（登录后回跳来源页）。任一校验失败：重渲染 `/register`，内联错误文案（i18n），已填用户名保留。（2026-09-19 实现期经用户确认修订：不采用"注册即手动写入 SecurityContext 自动登录"，以更简单安全的引导登录替代，规避会话固定处理复杂度。）

### 6.2 登录

Spring Security `formLogin`：`/login` 页、`/login` POST、失败 `?error=1` 内联提示"用户名或密码错误"（不区分账号不存在与密码错误）；成功后回跳 `SavedRequest`。

### 6.3 退出

`logout` POST 到 `/logout`（顶栏按钮为带 CSRF 令牌的小表单，非链接），销毁会话，回首页。

### 6.4 游客爬取 → 报告归属

游客爬取：任务照常执行，`seoReportService.save(taskId, url, null)` 落库（对任何用户不可见，仅保留数据）。登录用户爬取：报告 `user_id` = 本人，`/reports` 立即可见并可导出。

## 7. 页面与交互要求

- 登录/注册页复用现有暗色设计系统与 `app.css`，风格与全站一致；中英双语文案进 `messages*.properties`。
- 禁止浏览器原生交互（alert/confirm/原生校验气泡）：错误提示内联展示；前端 `pattern`/`minlength` 仅作即时提示，服务端校验为准。
- 首页"免费 · 免注册"宣传语保持不变；报告/自动更新入口对游客可见但点击引导登录。

## 8. 错误处理与安全

- 越权访问（他人报告/站点）统一 **404**，不泄露资源存在性。
- CSRF 全局开启；表单登录页由 Thymeleaf 自动注入令牌。
- 密码仅存 BCrypt 哈希；日志不打印密码；H2 console 保持关闭。
- 会话固定攻击防护：登录成功更换 session id（Spring Security 默认 `changeSessionId`）。
- 暴力破解不做处理（本地工具场景），文档留待后续限流项。

## 9. 测试策略（TDD：先写失败测试）

新增：

| 测试类 | 覆盖 |
|--------|------|
| `AuthIntegrationTest` | 注册成功 → `/login?registered=1` 引导登录，登录后回跳；重名/弱密码/两次不一致/非法用户名分别报对应错误；`POST /register` 缺 CSRF → 403；登录成功；错误密码 `?error`；已登录访问 `/login` → 302 首页；退出后会话失效 |
| `ReportAccessControlTest` | 游客 `/reports` → 200 空态 + 登录引导且不泄漏任何用户数据；`/report/{id}`、`/report/{id}/export` → 302 → `/login` 且无下载头；A 登录后看不到 B 的报告、访问 B 的报告/导出 → 404；A 的爬取任务完成后报告 `user_id` 绑定正确且列表可见 |
| `AutoAccessControlTest` | 游客 `GET /auto` → 200 空态 + 登录引导；`POST /auto`、详情/下载 → 302；B 不能 run/toggle/delete/push 配置 A 的站点（404）；`AutoSiteUpdater` 生成的报告归属站点所有者 |
| `UserServiceTest` | 密码哈希后可用 encoder 校验、明文不入库；用户名查重 |

改造存量：`ReportExportIntegrationTest`、`VisualHarnessTest` 及相关用例补 `csrf()` 与 `authentication()`，播种报告带 `user_id`。全量 306 项回归通过。

验证标准：`mvn test` 全绿；浏览器走查 8091 演示实例完成注册→登录→爬取→报告列表只见本人→三格式导出→退出→游客访问报告页被引导登录全流程。

## 10. 实施顺序概览

1. 依赖与 SecurityConfig 最小可用（默认登录页先行）+ 用户表/服务/注册登录页
2. 数据列与仓储按用户过滤 + 爬取归属绑定
3. 报告/导出/自动更新门禁与越权 404
4. 顶栏与页面文案、i18n
5. 存量测试改造 + 全量回归 + 浏览器走查

每步测试先行，最后整体走查。
