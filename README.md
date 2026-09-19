# 站点地图生成器

项目主线为 Java 应用，使用 Spring Boot 和 Jsoup 抓取网站链接并生成 XML 站点地图。Java 源码及页面资源位于 `src/`，构建由 Maven 管理。

## 环境要求

- 本机安装 JDK 21 或更高版本（构建需要完整 JDK），以及 Maven 3.9 或更高版本。
- 确保 `java`、`mvn` 可从命令行执行；用 `java -version`、`mvn --version` 核对版本，Maven 使用的 Java 也需满足要求。
- 首次获取依赖和构建插件需要网络。离线构建前，必须在同一依赖仓库中准备好所需依赖及插件。

## 启动

Windows 下运行统一入口：

```bat
start.bat
```

脚本会切换到自身目录、输出 Java/Maven 版本、执行 `mvn verify`（包含测试和打包，不清理历史产物、不跳过测试），然后在前台运行 `target/sitemap-studio-1.0.0.jar`。任何一步失败都会返回对应退出码；结束后恢复调用时的目录。

`run-server.bat`、`run-springboot.bat`、`quick-start.bat` 均只转调 `start.bat`，透传参数和退出码。脚本参数传给 Java 应用，例如：

```bat
start.bat --server.port=8081
```

默认访问地址为 **http://localhost:8080**。请等待 Spring Boot 日志确认启动成功后再访问；按 `Ctrl+C` 停止前台应用。如端口被占用，可使用上述端口参数。

也可以在项目根目录手动构建并启动（适用于 Windows、Linux、macOS）：

```sh
mvn verify
java -jar target/sitemap-studio-1.0.0.jar
```

## 测试与依赖缓存

在项目根目录执行：

```sh
mvn test
mvn verify
```

`test` 执行测试，`verify` 执行包含测试、打包及验证阶段的完整构建。实际结果以命令输出为准。

依赖及插件缓存齐备后，可离线构建：

```sh
mvn -o verify
java -jar target/sitemap-studio-1.0.0.jar
```

项目内的 `.mvn/maven.config` 和 HTTPS Maven settings 仅用于项目构建，不修改用户全局配置。如需沿用已有依赖缓存，可显式指定本地仓库（将占位内容替换为实际路径）：

```sh
mvn "-Dmaven.repo.local=<已有缓存目录绝对路径>" verify
```

`-o` 与 `-Dmaven.repo.local` 是 Maven 参数，可组合使用；请用于手动 Maven 命令，不要作为启动批处理的应用参数传入。离线失败时，应联网补齐所选仓库中的依赖及插件后再试。

## 使用范围与限制

- 抓取目标仅支持公开可访问的 HTTP(S) 站点，不支持内网、回环地址或本机文件；请遵守目标站点规则，仅对有权抓取的内容发起任务。
- 单次任务页面上限为 500，不保证收录站点全部页面；抓取结果受网络、站点访问限制及页面链接结构影响。
- 任务保存在内存中并会过期，重启也会丢失任务；请及时保存生成结果。

## 历史演示文件

旧 Python 脚本（包括 `simple-sitemap-server.py`）和历史测试页面保留供参考。Python 版本仅演示模拟站点地图生成，**不是真实爬虫**，也不是当前主线启动方式。历史页面及说明不作为 Java 主线功能或接口的验证依据。
