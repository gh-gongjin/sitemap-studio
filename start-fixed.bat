@echo off
echo ========================================
echo 修复版站点地图生成器启动脚本
echo ========================================
echo.

echo [1/4] 检查Java环境...
java -version 2>nul
if %errorlevel% neq 0 (
    echo ❌ Java未安装或未配置环境变量
    echo 请安装JDK（不仅仅是JRE）并设置JAVA_HOME
    pause
    exit /b 1
)

echo ✅ Java环境检查通过

echo.
echo [2/4] 检查Maven环境...
mvn -version 2>nul
if %errorlevel% neq 0 (
    echo ❌ Maven未安装或未配置环境变量
    echo 请安装Maven并配置环境变量
    pause
    exit /b 1
)

echo ✅ Maven环境检查通过

echo.
echo [3/4] 清理和编译项目...
echo 注意：如果出现"No compiler is provided"错误，说明只有JRE没有JDK
echo 请安装JDK并设置JAVA_HOME指向JDK目录

mvn clean compile -q
if %errorlevel% neq 0 (
    echo.
    echo ❌ 编译失败！
    echo 可能的原因：
    echo 1. 只有JRE没有JDK（安装JDK）
    echo 2. JAVA_HOME指向JRE而不是JDK
    echo 3. 环境变量配置不正确
    echo.
    echo 解决方案：
    echo 1. 下载并安装JDK 8或更高版本
    echo 2. 设置JAVA_HOME=C:\Program Files\Java\jdk1.8.0_xxx
    echo 3. 添加%JAVA_HOME%\bin到PATH
    echo 4. 重启命令行窗口
    pause
    exit /b 1
)

echo ✅ 编译成功！

echo.
echo [4/4] 启动Spring Boot应用...
echo 应用将在 http://localhost:8080 启动
echo 按Ctrl+C停止应用
echo.

mvn spring-boot:run

if %errorlevel% neq 0 (
    echo.
    echo ❌ 启动失败！
    echo 请检查以上错误信息
    pause
    exit /b 1
)