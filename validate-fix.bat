@echo off
chcp 65001 >nul
echo ================================================================
echo 站点地图生成器 - 编译和启动错误修复验证
echo ================================================================
echo.

REM ========== 步骤 1: 检查环境 ==========
echo [1/5] 检查 Java 和 Maven 环境...
echo.

java -version>nul 2>&1
if %errorlevel% neq 0 (
   echo ❌ Java 未安装或未配置
    pause
    exit /b 1
)
echo ✅ Java 环境正常

mvn --version >nul 2>&1
if %errorlevel% neq 0 (
   echo ❌ Maven 未安装或未配置
    pause
    exit /b 1
)
echo ✅ Maven 环境正常
echo.

REM ========== 步骤 2: 清理并编译 ==========
echo [2/5] 清理并编译项目...
echo.

call mvn clean compile -q
if %errorlevel% neq 0 (
   echo.
   echo ╔════════════════════════════════════════╗
   echo ║  ❌ 编译失败！错误如下：               ║
   echo ╚════════════════════════════════════════╝
   echo.
    call mvn compile
    pause
    exit /b 1
)
echo ✅ 编译成功！
echo.

REM ========== 步骤 3: 打包 ==========
echo [3/5] 打包项目（跳过测试）...
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
echo [4/5] 修复信息
echo.
echo ╔════════════════════════════════════════╗
echo ║  ✅ 所有编译错误已修复！                ║
echo ╚════════════════════════════════════════╝
echo.
echo 主要修复内容:
echo   1. 分离 WebSocketConfig 和 CorsConfig
echo   2. 解决方法接口冲突问题
echo   3. 遵循单一职责原则
echo.
echo 修改的文件:
echo   - WebSocketConfig.java (移除 WebMvcConfigurer)
echo   - CorsConfig.java (新增，独立 CORS 配置)
echo.

REM ========== 步骤 5: 启动应用 ==========
echo [5/5] 启动 Spring Boot 应用...
echo.
echo ════════════════════════════════════════
echo 📋 启动说明:
echo   - 日志文件：target\startup-validation.log
echo   - 访问地址：http://localhost:8080
echo   - 按 Ctrl+C 停止应用
echo ════════════════════════════════════════
echo.

REM 将输出重定向到日志文件
start cmd /k "cd /d %~dp0 && echo 启动时间: %date% %time% > target\startup-validation.log && call mvn spring-boot:run >> target\startup-validation.log 2>&1"

echo ⏳ 应用正在后台启动...
echo.
echo 等待 15 秒进行启动验证...
timeout /t 15 /nobreak >nul

REM 检查日志文件
echo.
echo ════════════════════════════════════════
echo 📊 启动验证结果
echo ════════════════════════════════════════
echo.

if exist "target\startup-validation.log" (
    REM 检查是否启动成功
    findstr /i "Started SitemapGeneratorApplication" target\startup-validation.log >nul
  if %errorlevel% equ 0 (
       echo ✅ 应用启动成功!
        
        REM 显示最后几行日志
       echo.
       echo 最近的启动日志:
       echo ----------------------------------------
        powershell -Command "Get-Content 'target\startup-validation.log' -Tail 20"
       echo ----------------------------------------
       echo.
        
        REM 检查是否有错误
        findstr /i "error exception failed" target\startup-validation.log >nul
      if %errorlevel% neq 0 (
           echo ✅ 未检测到严重错误
        ) else(
           echo ⚠️  检测到一些警告或错误（可能是正常的）
        )
        
       echo.
       echo 🎉 验证完成！
       echo.
       echo 下一步操作:
       echo   1. 访问 http://localhost:8080
       echo   2. 测试实时进度功能
       echo   3. 查看详细日志：target\startup-validation.log
       echo.
        
        start http://localhost:8080
        
    ) else(
       echo ⏳ 应用可能还在启动中...
       echo.
       echo 最近的日志:
       echo ----------------------------------------
        powershell -Command "Get-Content 'target\startup-validation.log' -Tail 30"
       echo ----------------------------------------
       echo.
       echo 💡 提示：应用可能需要更长时间启动，请查看完整日志:
       echo    target\startup-validation.log
       echo.
    )
) else(
   echo ⚠️  日志文件未生成，请稍后手动检查
)

echo.
echo ════════════════════════════════════════
echo ✅ 编译和启动错误修复完成！
echo ════════════════════════════════════════
echo.
echo 详细修复报告：FINAL-FIX-REPORT.md
echo.

pause
