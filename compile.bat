@echo off
echo 正在编译Spring Boot项目...
mvn clean compile

if %errorlevel% equ 0 (
    echo 编译成功！
) else (
    echo 编译失败！
    pause
    exit /b 1
)