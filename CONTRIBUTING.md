# Contributing to Sitemap Studio

English | [中文](#参与贡献)

Thanks for your interest in contributing! This project follows **GitHub Flow**.

## Setup

- JDK 21+ and Maven 3.9+
- `mvn verify` must pass locally before opening a PR (400+ tests, no skips allowed for new code paths)

## Branches & Pull Requests

1. Branch off `main` with a suggestive prefix: `feature/…`, `fix/…`, `docs/…`, `chore/…`
2. Keep PRs focused — one change per PR
3. Follow the PR template and tick every checklist item
4. CI must be green; a maintainer reviews and **squash-merges**; the branch is deleted afterwards
5. `main` is always releasable — do not merge anything that breaks `mvn verify`

## Conventions

- **Commits**: [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`, `refactor:`, `chore:`); subject may be Chinese or English
- **Backend**: Spring Boot layering — controllers stay thin, business logic in services, parameterized queries only
- **i18n**: every user-visible string needs both `messages.properties` (Chinese baseline) and `messages_en.properties`; keep the two files key-for-key in sync
- **Frontend**: no browser-native dialogs (`alert`/`confirm`/native validation bubbles) — use the in-house `SitemapUI` components in `app.js`
- **Crawler safety**: never weaken `CrawlUrlPolicy` (SSRF guard: internal/loopback/file targets must stay refused)

## Releases

Releases are cut by maintainers: version bump PR (`chore: release vX.Y.Z`) → merge → tag `vX.Y.Z` on `main` → the Release workflow publishes the jar to GitHub Releases.

---

<a name="参与贡献"></a>

# 参与贡献

感谢参与！本项目采用 **GitHub Flow** 协作模型。

## 环境准备

- JDK 21+ 与 Maven 3.9+
- 提 PR 前本地 `mvn verify` 必须全绿（400+ 用例；新增代码路径不允许被跳过）

## 分支与 Pull Request

1. 从 `main` 切短分支，前缀见名知意：`feature/…`、`fix/…`、`docs/…`、`chore/…`
2. 一个 PR 只做一件事
3. 按 PR 模板填写并逐项勾选清单
4. CI 必须通过；由维护者评审并 **squash 合并**，合并后删除分支
5. `main` 永远处于可发布状态——任何导致 `mvn verify` 失败的改动不得合入

## 项目约定

- **提交信息**：遵循 [Conventional Commits](https://www.conventionalcommits.org/)（`feat:`、`fix:`、`docs:`、`refactor:`、`chore:`），中英文均可
- **后端**：Spring Boot 分层——Controller 只做校验与转发，业务逻辑在 Service，数据库访问一律参数化查询
- **国际化**：所有用户可见文案必须同时写入 `messages.properties`（中文基准）与 `messages_en.properties`，两份文件逐 key 对齐
- **前端**：禁用浏览器原生弹窗（`alert`/`confirm`/原生校验气泡），统一使用 `app.js` 中的 `SitemapUI` 自研组件
- **爬虫安全**：不得弱化 `CrawlUrlPolicy`（SSRF 防线：内网、回环、本机文件目标必须保持拒绝）

## 版本发布

发布由维护者执行：版本号 PR（`chore: release vX.Y.Z`）→ 合并 → 在 `main` 上打 `vX.Y.Z` tag → Release 工作流自动把 jar 发布到 GitHub Releases。
