# 实时进度显示功能实现指南

## 功能概述

实现了类似 https://www.xml-sitemaps.com/的实时进度显示功能，用户可以在爬取过程中看到：
- 实时进度条（百分比）
- 已爬取的页面数量
- 当前正在爬取的 URL
- 已用时间
- 最近爬取的 URL 列表

## 技术栈

- **WebSocket**: 实时推送进度信息
- **STOMP 协议**: WebSocket 消息协议
- **Spring Boot WebSocket**: 后端支持
- **@stomp/stompjs**: 前端 WebSocket 客户端

## 文件清单

### 1. 后端文件

#### WebSocketConfig.java
**位置**: `src/main/java/io/github/ghgongjin/sitemap/config/WebSocketConfig.java`

**功能**: WebSocket 配置类
- 注册 STOMP 端点 `/ws-progress`
- 配置消息代理 `/topic`
- 支持 SockJS 降级兼容

```java
registry.addEndpoint("/ws-progress")
        .setAllowedOriginPatterns("*")
        .withSockJS();
```

#### CrawlProgressService.java
**位置**: `src/main/java/io/github/ghgongjin/sitemap/service/CrawlProgressService.java`

**功能**: 爬取进度追踪服务
- 管理所有爬取任务的进度
- 通过 WebSocket 推送进度更新
- 提供任务开始、更新、完成、失败的方法

**核心方法**:
- `startTask(taskId, url)` - 开始任务
- `updateProgress(taskId, crawledPages, totalPages, currentUrl)` - 更新进度
- `completeTask(taskId, totalUrls)` - 完成任务
- `failTask(taskId, errorMessage)` - 任务失败

#### EnhancedSitemapGeneratorService.java
**位置**: `src/main/java/io/github/ghgongjin/sitemap/service/EnhancedSitemapGeneratorService.java`

**修改内容**:
- 添加 `CrawlProgressService` 依赖注入
- 新增 `generateSitemapWithProgress()` 方法
- 新增 `enhancedCrawlWithProgress()` 方法
- 在爬取过程中调用 `progressService.updateProgress()`

**关键代码**:
```java
// 每次爬取页面时更新进度
if (progressService != null && taskId != null) {
   progressService.updateProgress(taskId, crawledUrls.size() + 1, MAX_PAGES, task.url);
}
```

#### SitemapController.java
**位置**: `src/main/java/io/github/ghgongjin/sitemap/controller/SitemapController.java`

**修改内容**:
- 注入 `CrawlProgressService`
- 新增 `/generate-async` 异步生成接口
- 在新线程中执行爬取任务
- 返回带任务 ID 的结果页面

**关键代码**:
```java
@PostMapping("/generate-async")
public String generateSitemapAsync(...) {
    String taskId = UUID.randomUUID().toString();
    
    // 异步执行
    new Thread(() -> {
        enhancedService.generateSitemapWithProgress(url, includeImages, includeVideos, taskId);
    }).start();
    
   return "result-progress";
}
```

### 2. 前端文件

#### index.html
**位置**: `src/main/resources/templates/index.html`

**修改内容**:
- 添加进度显示容器
- 集成 STOMP.js 库
- 实现 WebSocket 连接和消息处理
- 表单提交改为异步方式

**关键功能**:
```javascript
// 连接到 WebSocket
stompClient = Stomp.over(new WebSocket('/ws-progress'));

// 订阅进度消息
stompClient.subscribe('/topic/progress/' + taskId, message => {
   const progress = JSON.parse(message.body);
    handleProgressUpdate(progress);
});

// 异步提交表单
fetch('/generate-async', { ... })
```

#### result-progress.html
**位置**: `src/main/resources/templates/result-progress.html`

**功能**: 专门的进度显示页面
- 大字体进度条
- 实时统计信息（已爬取、总数、时间）
- 最近爬取的 URL 列表（滚动显示）
- 自动跳转到结果预览页

**UI 特性**:
- 进度条颜色随状态变化（蓝色→绿色/红色）
- 动画旋转图标
- URL 列表自动滚动
- 响应式设计

## 工作流程

### 1. 用户操作
1. 用户在首页输入 URL
2. 点击"生成站点地图"按钮
3. 表单被异步提交到 `/generate-async`

### 2. 后端处理
1. 控制器生成唯一 `taskId`
2. 启动新线程执行爬取
3. 立即返回进度页面（带 taskId）
4. 爬取过程中不断推送进度

### 3. WebSocket 通信
```
客户端                        服务器端
  |                            |
  |-- Connect /ws-progress --->|
  |                            |
  |<-- Connection Established -|
  |                            |
  |-- Subscribe /topic/progress/{taskId}
  |                            |
  |                            |-- 爬取开始
  |<-- Progress: 0% -----------|
  |                            |
  |                            |-- 爬取中...
  |<-- Progress: 15% -----------|
  |<-- Progress: 30% -----------|
  |<-- Progress: 45% -----------|
  |                            |
  |                            |-- 爬取完成
  |<-- Progress: 100% ----------|
  |                            |
  |-- Redirect to preview -----|
```

### 4. 前端更新
1. 接收进度消息（JSON 格式）
2. 更新进度条宽度
3. 更新统计数字
4. 添加到 URL 历史列表
5. 完成后自动跳转

## 进度消息格式

```typescript
interface ProgressMessage {
    taskId: string;          // 任务 ID
    status: string;          // 'started' | 'progressing' | 'completed' | 'failed'
   message: string;         // 显示消息
    percentage: number;      // 进度百分比 (0-100)
    crawledPages: number;    // 已爬取页数
    totalPages: number;      // 总页数（预估）
    currentUrl: string;      // 当前爬取的 URL
    timestamp: number;       // 时间戳
}
```

## 配置说明

### pom.xml 依赖

已添加 WebSocket 支持：
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
```

### 性能参数

可在 `application.properties` 中调整：
```properties
# 最大页面数（影响进度计算）
sitemap.generator.max-pages=500

# 超时时间
sitemap.generator.timeout-ms=30000

# 并发线程数
sitemap.generator.max-threads=20
```

## 使用示例

### 1. 基本使用
```bash
# 启动应用
mvn spring-boot:run

# 访问首页
http://localhost:8080

# 输入 URL 并点击生成
https://www.example.com
```

### 2. 查看进度
- 页面自动跳转到进度页
- 实时显示爬取进度
- 等待完成后自动跳转预览

### 3. 调试模式
打开浏览器控制台查看日志：
```javascript
Connected: CONNECTED
进度更新：{taskId: "...", status: "progressing", percentage: 45, ...}
```

## 优化建议

### 1. 进度估算优化
当前进度基于 `crawledPages/ MAX_PAGES` 计算，可能不够准确。可以改进为：
```java
// 智能估算总页数
int estimatedTotal = Math.max(crawledPages * 2, 100);
double percentage = (crawledPages * 100.0 / estimatedTotal);
```

### 2. 断线重连
添加 WebSocket 断线重连机制：
```javascript
function connectWebSocket() {
    stompClient = Stomp.over(new WebSocket('/ws-progress'));
    
    stompClient.reconnect_delay = 5000; // 5 秒后重连
    
    stompClient.connect(...);
}
```

### 3. 多标签页支持
使用房间概念隔离不同任务：
```java
// 加入任务房间
@MessageMapping("/join/{taskId}")
public void joinTask(@DestinationVariable String taskId) {
    // 将用户加入特定任务的订阅列表
}
```

### 4. 进度持久化
将进度保存到 Redis，支持页面刷新：
```java
@Autowired
private RedisTemplate<String, Object> redisTemplate;

public void updateProgress(...) {
    // 发送到 WebSocket
   messagingTemplate.convertAndSend(...);
    
    // 保存到 Redis
   redisTemplate.opsForValue().set("progress:" + taskId, progressData);
}
```

## 故障排查

### 1. WebSocket 连接失败
**现象**: 控制台显示连接错误
**解决**: 
- 检查是否添加了 WebSocket 依赖
- 确认端口未被防火墙阻止
- 检查 CORS 配置

### 2. 进度不更新
**现象**: 进度条一直显示 0%
**解决**:
- 检查 `taskId`是否正确传递
- 确认 `progressService` 已注入
- 查看后端日志是否有异常

### 3. 内存溢出
**现象**: 爬取大量页面时 OOM
**解决**:
- 增加 JVM 堆内存：`-Xmx2g`
- 减少 `MAX_PAGES` 限制
- 优化 URL 去重逻辑

## 与 xml-sitemaps.com 对比

| 功能 | xml-sitemaps.com | 本项目 | 状态 |
|------|------------------|--------|------|
| 实时进度条 | ✅ | ✅ | ✅ |
| 百分比显示 | ✅ | ✅ | ✅ |
| 已爬取数量 | ✅ | ✅ | ✅ |
| 当前 URL | ✅ | ✅ | ✅ |
| 已用时间 | ✅ | ✅ | ✅ |
| URL 列表 | ✅ | ✅ | ✅ |
| 预估剩余时间 | ✅ | ❌ | 🔄 待实现 |
| 暂停/继续 | ✅ | ❌ | 🔄 待实现 |
| 多任务并行 | ✅ | ❌ | 🔄 待实现 |

## 下一步计划

1. **预估剩余时间**
   - 基于当前爬取速度计算
   - 动态更新预估值

2. **暂停/继续功能**
   - 添加暂停按钮
   - 保存当前进度
   - 支持恢复爬取

3. **多任务支持**
   - 同时爬取多个网站
   - 任务列表管理
   - 优先级队列

4. **进度分享**
   - 生成进度分享链接
   - 实时协作查看
   - 导出进度报告

---

*实现时间：2026-03-09*  
*版本：v1.0*  
*参考：https://www.xml-sitemaps.com/*
