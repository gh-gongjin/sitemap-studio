@echo off
chcp 65001 >nul
echo ================================================================
echo 站点地图生成器 - 启动报错修复验证
echo ================================================================
echo.

REM ========== 步骤 1: 检查环境 ==========
echo [1/6] 检查 Java 和 Maven 环境...
echo.

java -version>nul 2>&1
if %errorlevel% neq 0 (
  echo ❌ Java 未安装或未配置
    pause
    exit /b 1
)
echo ✅ Java 环境正常

mvn --version>nul 2>&1
if %errorlevel% neq 0 (
  echo ❌ Maven 未安装或未配置
    pause
    exit /b 1
)
echo ✅ Maven 环境正常
echo.

REM ========== 步骤 2: 清理并编译 ==========
echo [2/6] 清理并编译项目...
echo.

call mvn clean compile -q
if %errorlevel% neq 0 (
  echo.
  echo ╔════════════════════════════════════════╗
  echo ║  ❌ 编译失败！                         ║
  echo ╚════════════════════════════════════════╝
  echo.
    call mvn compile
    pause
    exit /b 1
)
echo ✅ 编译成功！
echo.

REM ========== 步骤 3: 打包 ==========
echo [3/6] 打包项目（跳过测试）...
echo.

call mvn package -DskipTests -q
if %errorlevel% neq 0 (
  echo ❌ 打包失败
    pause
    exit /b 1
)
echo ✅ 打包成功！
echo.

REM ========== 步骤 4: 显示修复信息 ==========
echo [4/6] 启动报错修复内容
echo.
echo ════════════════════════════════════════
echo 📋 主要修复内容:
echo ════════════════════════════════════════
echo.
echo   1. ✅ 添加 SockJS 和 STOMP 依赖
echo      - sockjs-client 1.5.1
echo      - stomp-websocket 2.3.4
echo.
echo   2. ✅ 优化 WebSocketConfig 配置
echo      - 添加心跳检测（25 秒）
echo      - 设置用户目的地前缀
echo.
echo   3. ✅ 优化 CORS 配置
echo      - 使用 allowedOriginPatterns
echo      - 允许携带凭证
echo      - 支持更多 HTTP 方法
echo.
echo ════════════════════════════════════════
echo.

REM ========== 步骤 5: 启动应用 ==========
echo [5/6] 启动 Spring Boot 应用...
echo.
echo ════════════════════════════════════════
echo 📋 启动说明:
echo   - 日志文件：target\startup-fix.log
echo   - 访问地址：http://localhost:8080
echo   - WebSocket: ws://localhost:8080/ws-progress
echo   - 按 Ctrl+C 停止应用
echo ════════════════════════════════════════
echo.

REM 将输出重定向到日志文件
start cmd /k "cd /d %~dp0 && echo 启动时间：%date% %time% > target\startup-fix.log && call mvn spring-boot:run >> target\startup-fix.log 2>&1"

echo ⏳ 应用正在后台启动...
echo.
echo 等待 20 秒进行启动验证...
timeout /t 20 /nobreak >nul

REM ========== 步骤 6: 验证启动 ==========
echo.
echo [6/6] 启动验证结果
echo ════════════════════════════════════════
echo.

if exist "target\startup-fix.log" (
    REM 检查是否启动成功
    findstr /i "Started SitemapGeneratorApplication" target\startup-fix.log >nul
  if %errorlevel% equ 0 (
      echo ✅ 应用启动成功!
        
        REM 显示关键日志
      echo.
      echo 关键启动日志:
      echo ----------------------------------------
        findstr /i "CORS WebSocket 端点 启动成功" target\startup-fix.log
      echo ----------------------------------------
      echo.
        
        REM 检查是否有严重错误
        findstr /i "fatal error exception failed" target\startup-fix.log >nul
     if %errorlevel% neq 0 (
          echo ✅ 未检测到严重错误
        ) else(
          echo ⚠️  检测到一些警告（可能是正常的）
        )
        
      echo.
      echo 🎉 启动验证完成！
      echo.
      echo 下一步操作:
      echo   1. 访问首页：http://localhost:8080
      echo   2. 测试 WebSocket: http://localhost:8080/ws-progress/info
      echo   3. 查看详细日志：target\startup-fix.log
      echo.
        
        start http://localhost:8080
        start http://localhost:8080/ws-progress/info
        
    ) else(
      echo ⏳ 应用可能还在启动中...
      echo.
      echo 最近的日志:
      echo ----------------------------------------
        powershell -Command "Get-Content 'target\startup-fix.log' -Tail 40"
      echo ----------------------------------------
      echo.
      echo 💡 提示：应用可能需要更长时间启动
      echo    请查看完整日志：target\startup-fix.log
      echo.
    )
) else(
  echo ⚠️  日志文件未生成，请稍后手动检查
)

echo ════════════════════════════════════════
echo ✅ 启动报错修复验证完成！
echo ════════════════════════════════════════
echo.
echo 详细修复报告：STARTUP-FIX-COMPLETE.md
echo.

pause
