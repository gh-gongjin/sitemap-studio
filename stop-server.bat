@echo off
echo ========================================
echo 停止站点地图生成器服务
echo ========================================
echo.

echo 1. 停止Python服务器进程...
taskkill /F /IM python.exe 2>nul
taskkill /F /IM python3.exe 2>nul

echo 2. 释放端口8088...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8088') do (
    echo 发现进程占用端口8088: PID=%%a
    taskkill /F /PID %%a 2>nul
)

echo 3. 停止Java/Maven进程...
taskkill /F /IM java.exe 2>nul
taskkill /F /IM mvn.cmd 2>nul

echo.
echo 4. 检查端口状态...
netstat -ano | findstr :8088 >nul
if %errorlevel% equ 0 (
    echo ❌ 端口8088仍被占用
) else (
    echo ✅ 端口8088已释放
)

echo.
echo ========================================
echo 服务停止完成！
echo ========================================
echo.
echo 如果还需要停止其他服务，请检查：
echo - 端口8080（原Java Spring Boot端口）
echo - 其他Python进程
echo.
pause