@echo off
chcp 65001 >nul
echo ==================================================
echo 启动 Spring Boot 应用并捕获错误
echo ==================================================
echo.

REM 检查 Java 环境
echo 检查 Java 环境...
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo ❌ 错误：Java 未安装或未配置环境变量
    pause
    exit /b 1
)
echo ✅ Java 环境正常
echo.

REM 检查 Maven 环境
echo 检查 Maven 环境...
mvn --version >nul 2>&1
if %errorlevel% neq 0 (
    echo ❌ 错误：Maven 未安装或未配置环境变量
    pause
    exit /b 1
)
echo ✅ Maven 环境正常
echo.

REM 编译项目
echo 编译项目...
call mvn clean package -DskipTests -q
if %errorlevel% neq 0 (
    echo ❌ 编译失败，请查看上面的错误信息
    pause
    exit /b 1
)
echo ✅ 编译成功
echo.

REM 启动应用
echo ==================================================
echo 启动 Spring Boot 应用...
echo 日志文件：target/spring-boot-startup.log
echo ==================================================
echo.

REM 将输出重定向到日志文件
start cmd /k "cd /d %~dp0 && call mvn spring-boot:run > target\spring-boot-startup.log 2>&1"

echo 应用正在后台启动...
echo.
echo 等待 10 秒检查启动状态...
timeout /t 10 /nobreak >nul

REM 检查日志文件
if exist "target\spring-boot-startup.log" (
    echo.
    echo ========== 最近的启动日志 ==========
    powershell -Command "Get-Content 'target\spring-boot-startup.log' -Tail 50"
    echo =====================================
    echo.
    
    REM 检查是否有错误
    findstr /i "error exception failed" target\spring-boot-startup.log >nul
   if %errorlevel% equ 0 (
        echo ❌ 检测到启动错误！
        echo.
        echo 完整日志位置：%~dp0target\spring-boot-startup.log
        echo.
        echo 按任意键打开日志文件...
        pause
        notepad target\spring-boot-startup.log
    ) else(
        echo ✅ 应用似乎正常启动
        echo.
        echo 访问地址：http://localhost:8080
        start http://localhost:8080
    )
) else(
    echo ⚠️ 日志文件未生成
)

echo.
pause
