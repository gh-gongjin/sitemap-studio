@echo off
chcp 65001 >nul
echo ================================
echo 服务状态检查
echo ================================
echo.

echo 检查端口8088...
netstat -ano | findstr :8088 >nul
if errorlevel 1 (
    echo [OK] 端口8088空闲
) else (
    echo [ERROR] 端口8088被占用
    netstat -ano | findstr :8088
)

echo.
echo 检查Python进程...
tasklist | findstr /i python.exe >nul
if errorlevel 1 (
    echo [OK] 没有Python进程
) else (
    echo [ERROR] 发现Python进程
    tasklist | findstr /i python.exe
)

echo.
echo 检查Java进程...
tasklist | findstr /i java.exe >nul
if errorlevel 1 (
    echo [OK] 没有Java进程
) else (
    echo [ERROR] 发现Java进程
    tasklist | findstr /i java.exe
)

echo.
echo ================================
echo 总结：
echo - 要启动服务: 运行 run-server.bat
echo - 要停止服务: 运行 stop-server.bat
echo ================================
pause