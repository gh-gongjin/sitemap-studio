@echo off
echo ========================================
echo 站点地图生成器服务状态检查
echo ========================================
echo.

echo [检查时间] %date% %time%
echo.

echo 1. 检查Python进程...
tasklist | findstr /i "python" >nul
if %errorlevel% equ 0 (
    echo ❌ 发现Python进程正在运行
    tasklist | findstr /i "python"
) else (
    echo ✅ 没有Python进程运行
)

echo.
echo 2. 检查Java/Maven进程...
tasklist | findstr /i "java maven" >nul
if %errorlevel% equ 0 (
    echo ❌ 发现Java/Maven进程正在运行
    tasklist | findstr /i "java maven"
) else (
    echo ✅ 没有Java/Maven进程运行
)

echo.
echo 3. 检查端口占用情况...
echo 端口8080 (原Spring Boot端口):
netstat -ano | findstr :8080 >nul
if %errorlevel% equ 0 (
    echo ❌ 端口8080被占用
    netstat -ano | findstr :8080
) else (
    echo ✅ 端口8080空闲
)

echo.
echo 端口8088 (Python服务器端口):
netstat -ano | findstr :8088 >nul
if %errorlevel% equ 0 (
    echo ❌ 端口8088被占用
    netstat -ano | findstr :8088
) else (
    echo ✅ 端口8088空闲
)

echo.
echo 4. 检查服务文件...
if exist "simple-sitemap-server.py" (
    echo ✅ 找到Python服务器文件
) else (
    echo ❌ 未找到Python服务器文件
)

if exist "run-server.bat" (
    echo ✅ 找到启动脚本
) else (
    echo ❌ 未找到启动脚本
)

if exist "stop-server.bat" (
    echo ✅ 找到停止脚本
) else (
    echo ❌ 未找到停止脚本
)

echo.
echo ========================================
echo 状态检查完成
echo ========================================
echo.
echo 可用命令：
echo - 启动服务: run-server.bat
echo - 停止服务: stop-server.bat
echo - 检查状态: check-status.bat
echo.
pause