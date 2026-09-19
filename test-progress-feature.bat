@echo off
chcp 65001 >nul
echo ==================================================
echo 站点地图生成器 - 实时进度功能测试
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
call mvn clean compile -q
if %errorlevel% neq 0 (
    echo ❌ 错误：编译失败
    pause
    exit /b 1
)
echo ✅ 编译成功
echo.

REM 启动应用
echo ==================================================
echo 启动 Spring Boot 应用...
echo.
echo 访问地址：http://localhost:8080
echo 测试 URL: https://www.baidu.com
echo.
echo 功能特性:
echo   ✅ 实时进度条显示
echo   ✅ 已爬取页面数量
echo   ✅ 当前爬取的 URL
echo   ✅ 已用时间统计
echo   ✅ URL 历史列表
echo   ✅ 完成后自动跳转
echo.
echo ==================================================
echo.
echo 按 Ctrl+C 停止应用
echo.

REM 启动应用
start mvn spring-boot:run

echo 应用已在后台启动...
echo.
echo 浏览器将自动打开，或手动访问：http://localhost:8080
echo.
timeout /t 3
start http://localhost:8080
echo.
pause
