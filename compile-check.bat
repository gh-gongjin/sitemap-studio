@echo off
chcp 65001 >nul
echo ==================================================
echo 编译项目...
echo ==================================================
echo.

call mvn clean compile -q

if %errorlevel% equ 0 (
    echo ✅ 编译成功！
) else(
    echo ❌ 编译失败，错误如下:
    call mvn clean compile
)

echo.
pause
