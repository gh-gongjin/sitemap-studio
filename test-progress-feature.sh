#!/bin/bash
# 实时进度功能测试脚本

echo "=================================================="
echo "站点地图生成器 - 实时进度功能测试"
echo "=================================================="
echo ""

# 检查 Java 环境
echo "检查 Java 环境..."
java -version >nul 2>&1
if [ $? -ne 0 ]; then
    echo "❌ 错误：Java 未安装或未配置环境变量"
    exit 1
fi
echo "✅ Java 环境正常"
echo ""

# 检查 Maven 环境
echo "检查 Maven 环境..."
mvn --version >nul 2>&1
if [ $? -ne 0 ]; then
    echo "❌ 错误：Maven 未安装或未配置环境变量"
    exit 1
fi
echo "✅ Maven 环境正常"
echo ""

# 编译项目
echo "编译项目..."
mvn clean compile -q
if [ $? -ne 0 ]; then
    echo "❌ 错误：编译失败"
    exit 1
fi
echo "✅ 编译成功"
echo ""

# 启动应用
echo "=================================================="
echo "启动 Spring Boot 应用..."
echo "访问地址：http://localhost:8080"
echo "测试 URL: https://www.baidu.com"
echo "=================================================="
echo ""
echo "按 Ctrl+C 停止应用"
echo ""

# 启动应用（前台）
mvn spring-boot:run
