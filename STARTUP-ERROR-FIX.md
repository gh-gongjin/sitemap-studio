# 服务启动错误修复指南

## 已完成的修复

### 1. ✅ CORS 配置问题

**问题**: WebSocket 配置中使用了 `setAllowedOriginPatterns("*")`，某些 Spring Boot 版本可能不支持

**修复**: 
- 改用 `setAllowedOrigins("*")`
- 添加完整的 CORS 配置支持
- 实现 `WebMvcConfigurer` 接口处理跨域请求

**修改文件**:
```java
// WebSocketConfig.java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer, WebMvcConfigurer {
    
    @Override
   public void registerStompEndpoints(StompEndpointRegistry registry) {
      registry.addEndpoint("/ws-progress")
                .setAllowedOrigins("*")  // ✅ 兼容性更好
               .withSockJS();
   }
   
   @Override
   public void addCorsMappings(CorsRegistry registry) {
      registry.addMapping("/**")
               .allowedOrigins("*")
               .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
               .allowedHeaders("*")
               .maxAge(3600);
   }
}
```

### 2. ✅ 依赖注入问题

**已确认**:
- [SitemapController](file://F:\projects\ai\sitemap-generator\src\main\java\io\github\ghgongjin\sitemap\controller\SitemapController.java) 正确注入了所有服务
- [CrawlProgressService](file://F:\projects\ai\sitemap-generator\src\main\java\io\github\ghgongjin\sitemap\service\CrawlProgressService.java) 使用构造函数注入
- [EnhancedSitemapGeneratorService](file://F:\projects\ai\sitemap-generator\src\main\java\io\github\ghgongjin\sitemap\service\EnhancedSitemapGeneratorService.java) 提供了 setter 方法

### 3. ✅ 编译错误

**状态**: 所有编译错误已修复
- ✅ `enhancedService` 字段已声明
- ✅ `generateSitemapWithProgress` 方法已添加
- ✅ 所有 import 语句完整

---

## 可能的启动错误及解决方案

### 错误 1: 端口被占用

**错误信息**:
```
Port 8080 was already in use. 
```

**解决方案**:
1. **查找并关闭占用端口的进程**
   ```bash
   # Windows
   netstat -ano | findstr :8080
   taskkill /F /PID <PID>
   
   # Linux/Mac
  lsof -i :8080
   kill -9 <PID>
   ```

2. **修改应用端口**
   编辑 `application.properties`:
   ```properties
   server.port=8081
   ```

### 错误 2: WebSocket 依赖冲突

**错误信息**:
```
ClassNotFoundException: org.springframework.web.socket.WebSocketHandler
```

**解决方案**:
确认 pom.xml 中包含以下依赖:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
```

### 错误 3: STOMP 消息代理初始化失败

**错误信息**:
```
IllegalStateException: No broker configured
```

**解决方案**:
确认 WebSocketConfig 中配置了消息代理:
```java
@Override
public void configureMessageBroker(MessageBrokerRegistry registry) {
  registry.enableSimpleBroker("/topic");
  registry.setApplicationDestinationPrefixes("/app");
}
```

### 错误 4: Lombok 未正确处理

**错误信息**:
```
Cannot resolve symbol 'log'
Constructor not found
```

**解决方案**:
1. 确保 IDE 安装了 Lombok 插件
2. 启用注解处理器:
   - IntelliJ: Settings → Build → Compiler → Annotation Processors → Enable
   - Eclipse: Properties → JDT Weaving → Enable weaver

### 错误 5: Spring Boot 版本不兼容

**错误信息**:
```
IncompatibleClassChangeError
NoSuchMethodError
```

**解决方案**:
检查 pom.xml 中的 Spring Boot 版本:
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>2.7.18</version>
</parent>
```

确保所有依赖与此版本兼容。

---

## 诊断步骤

### 步骤 1: 查看详细日志

运行应用并查看日志:
```bash
mvn spring-boot:run > target/startup.log 2>&1
```

查看日志文件:
```bash
# Windows
type target\startup.log

# Linux/Mac
cat target/startup.log
```

### 步骤 2: 检查关键组件

创建测试类验证配置:

```java
@SpringBootTest
public class StartupTest {
    
    @Autowired
   private WebSocketConfig webSocketConfig;
    
    @Autowired
   private CrawlProgressService progressService;
    
    @Autowired
   private SitemapController controller;
    
    @Test
   public void testBeansLoaded() {
        assertNotNull(webSocketConfig);
        assertNotNull(progressService);
        assertNotNull(controller);
    }
}
```

### 步骤 3: 验证 WebSocket 连接

启动后访问:
```
http://localhost:8080/ws-progress/info
```

应该返回 JSON 信息，表示 WebSocket 端点正常。

---

## 快速修复脚本

### Windows (check-startup-error.bat)

```batch
@echo off
chcp 65001 >nul
echo 正在检查启动错误...

REM 编译项目
call mvn clean package -DskipTests -q

REM 启动应用并重定向日志
start cmd /k "mvn spring-boot:run > target\startup.log 2>&1"

REM 等待 10 秒
timeout /t 10 /nobreak >nul

REM 检查错误
findstr /i "error exception failed" target\startup.log >nul
if %errorlevel% equ 0 (
   echo ❌ 检测到错误
    type target\startup.log
) else(
   echo ✅ 启动成功
    start http://localhost:8080
)

pause
```

### Linux/Mac (check-startup-error.sh)

```bash
#!/bin/bash
echo "正在检查启动错误..."

# 编译项目
mvn clean package -DskipTests -q

# 后台启动
mvn spring-boot:run > target/startup.log 2>&1 &
PID=$!

# 等待 10 秒
sleep 10

# 检查进程是否还在运行
if ps -p $PID > /dev/null; then
   echo "✅ 应用正在运行 (PID: $PID)"
    
    # 检查日志中的错误
   if grep -qi "error\|exception\|failed" target/startup.log; then
       echo "⚠️  检测到警告或错误:"
        tail -50 target/startup.log
    else
       echo "✅ 启动成功"
        open http://localhost:8080
    fi
else
   echo "❌ 应用启动失败"
    cat target/startup.log
fi
```

---

## 验证清单

启动前检查:

- [ ] Java 环境已配置 (JAVA_HOME)
- [ ] Maven 环境已配置
- [ ] 端口 8080 未被占用
- [ ] Lombok 插件已安装并启用
- [ ] IDE 注解处理器已启用
- [ ] 所有依赖已下载

启动后验证:

- [ ] 控制台显示 "Started SitemapGeneratorApplication"
- [ ] 可以访问 http://localhost:8080
- [ ] WebSocket 端点可用 (/ws-progress/info)
- [ ] 没有 ERROR 级别的日志

功能测试:

- [ ] 输入 URL 可以生成站点地图
- [ ] 进度条实时显示
- [ ] WebSocket 连接正常
- [ ] 完成后自动跳转

---

## 常见错误关键词速查

在日志中搜索这些关键词快速定位问题:

| 关键词 | 含义 | 解决方案 |
|--------|------|----------|
| `Port ... in use` | 端口被占用 | 关闭占用进程或修改端口 |
| `ClassNotFoundException` | 缺少依赖 | 检查 pom.xml |
| `NoSuchMethodError` | 版本冲突 | 统一依赖版本 |
| `IllegalStateException` | 配置错误 | 检查配置类 |
| `NullPointerException` | 空指针 | 检查依赖注入 |
| `TimeoutException` | 超时 | 增加超时时间 |
| `BeanCreationException` | Bean 创建失败 | 检查@Component/@Service 注解 |

---

## 联系支持

如果以上方法都无法解决问题，请提供:

1. 完整的启动日志 (target/startup.log)
2. pom.xml 内容
3. application.properties 内容
4. 操作系统和 Java 版本信息

---

*更新时间：2026-03-09*  
*状态：已修复 CORS 配置问题 ✅*
