@echo off
echo ========================================
echo Java和Maven环境诊断工具
echo ========================================
echo.

echo [1/4] 检查Java安装...
where java >nul 2>&1
if %errorlevel% equ 0 (
    for /f "tokens=*" %%i in ('where java') do (
        echo Java可执行文件位置: %%i
    )
) else (
    echo ❌ 未找到Java可执行文件
)

echo.
echo [2/4] 检查Java版本...
java -version 2>&1 | findstr "version"
if %errorlevel% equ 0 (
    echo ✅ Java版本检查通过
) else (
    echo ❌ 无法获取Java版本
)

echo.
echo [3/4] 检查JAVA_HOME环境变量...
if defined JAVA_HOME (
    echo JAVA_HOME: %JAVA_HOME%
    
    if exist "%JAVA_HOME%\bin\javac.exe" (
        echo ✅ JAVA_HOME指向JDK（包含javac编译器）
    ) else (
        echo ⚠️  JAVA_HOME可能指向JRE而不是JDK
        echo    在%JAVA_HOME%中未找到javac.exe
    )
    
    if exist "%JAVA_HOME%\lib\tools.jar" (
        echo ✅ 找到tools.jar（JDK标志）
    ) else (
        echo ⚠️  未找到tools.jar（可能是JRE）
    )
) else (
    echo ❌ JAVA_HOME环境变量未设置
)

echo.
echo [4/4] 检查Maven...
where mvn >nul 2>&1
if %errorlevel% equ 0 (
    for /f "tokens=*" %%i in ('where mvn') do (
        echo Maven可执行文件位置: %%i
    )
    mvn -version 2>&1 | findstr "Apache Maven"
    echo ✅ Maven检查通过
) else (
    echo ❌ 未找到Maven可执行文件
)

echo.
echo ========================================
echo 诊断结果：
echo ========================================

where javac >nul 2>&1
if %errorlevel% equ 0 (
    echo ✅ 系统中有javac编译器（有JDK）
) else (
    echo ❌ 系统中没有javac编译器（只有JRE）
    echo.
    echo ⚠️  问题诊断：
    echo     当前环境只有Java Runtime Environment (JRE)
    echo     但编译Java项目需要Java Development Kit (JDK)
    echo.
    echo 💡 解决方案：
    echo     1. 下载JDK 8或更高版本：https://www.oracle.com/java/technologies/downloads/
    echo     2. 安装JDK（注意不是JRE）
    echo     3. 设置JAVA_HOME指向JDK安装目录
    echo     4. 添加%JAVA_HOME%\bin到PATH环境变量
    echo     5. 重启命令行窗口
)

echo.
echo 按任意键退出...
pause >nul