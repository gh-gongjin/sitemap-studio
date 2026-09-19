"""
对比测试：本地项目 vs xml-sitemaps.com
测试 URL: http://www.hnzwgs.com/
"""

import requests
import xml.etree.ElementTree as ET
from datetime import datetime
import time

def count_urls_in_sitemap(xml_content):
    """统计站点地图中的 URL 数量"""
    try:
        # 解析 XML
        root = ET.fromstring(xml_content)
        # 查找所有 loc 标签
        urls = root.findall('.//{http://www.sitemaps.org/schemas/sitemap/0.9}loc')
        return len(urls), [url.text for url in urls]
    except Exception as e:
       print(f"解析 XML 失败：{e}")
        return 0, []

def test_local_sitemap():
    """测试本地项目生成的站点地图"""
   print("=" * 60)
   print("测试本地项目")
   print("=" * 60)
    
    try:
        # 调用本地 API
        url = "http://localhost:8080/api/generate"
        params = {
            "url": "http://www.hnzwgs.com/",
            "includeImages": "false",
            "includeVideos": "false"
        }
        
       print(f"请求 URL: {url}")
       print(f"参数：{params}")
       print("开始生成站点地图...")
        
        start_time = time.time()
        response = requests.get(url, params=params, timeout=120)
        end_time = time.time()
        
       if response.status_code == 200:
           xml_content = response.text
            url_count, urls = count_urls_in_sitemap(xml_content)
            
           print(f"\n✅ 生成成功!")
           print(f"耗时：{end_time - start_time:.2f}秒")
           print(f"URL 数量：{url_count}")
           print(f"响应大小：{len(xml_content)}字节")
            
            # 保存 XML 到文件
            with open("local_sitemap.xml", "w", encoding="utf-8") as f:
                f.write(xml_content)
           print("XML 已保存到：local_sitemap.xml")
            
            # 显示前 10 个 URL
           if urls:
               print("\n前 10 个 URL:")
                for i, url in enumerate(urls[:10], 1):
                   print(f"{i}. {url}")
            
            return url_count, urls, end_time - start_time
        else:
           print(f"❌ 请求失败：HTTP {response.status_code}")
           print(f"响应内容：{response.text[:500]}")
            return 0, [], 0
            
    except requests.exceptions.Timeout:
       print("❌ 请求超时")
        return 0, [], 0
    except requests.exceptions.ConnectionError:
       print("❌ 连接失败：请确保本地服务正在运行 (端口 8080)")
        return 0, [], 0
    except Exception as e:
       print(f"❌ 发生错误：{e}")
        return 0, [], 0

def test_xml_sitemaps_com():
    """测试 xml-sitemaps.com 在线平台"""
   print("\n" + "=" * 60)
   print("测试 xml-sitemaps.com 在线平台")
   print("=" * 60)
   print("\n⚠️  由于 xml-sitemaps.com 没有公开 API，需要手动测试")
   print("\n请按以下步骤操作:")
   print("1. 访问 https://www.xml-sitemaps.com/")
   print("2. 在输入框中输入：http://www.hnzwgs.com/")
   print("3. 点击 'Start' 按钮")
   print("4. 等待爬取完成")
   print("5. 查看结果页面，记录找到的 URL 数量")
   print("6. 下载 XML 文件并保存为 online_sitemap.xml")
   print("\n完成后按回车键继续...")
    input()
    
    # 尝试读取已保存的文件
    try:
        with open("online_sitemap.xml", "r", encoding="utf-8") as f:
           xml_content = f.read()
        
        url_count, urls = count_urls_in_sitemap(xml_content)
       print(f"\n✅ 从文件读取成功!")
       print(f"URL 数量：{url_count}")
       print(f"文件大小：{len(xml_content)}字节")
        
        # 显示前 10 个 URL
       if urls:
           print("\n前 10 个 URL:")
            for i, url in enumerate(urls[:10], 1):
               print(f"{i}. {url}")
        
        return url_count, urls
        
    except FileNotFoundError:
       print("❌ 未找到 online_sitemap.xml 文件")
       print("请先手动下载并保存文件后再运行此脚本")
        return 0, []
    except Exception as e:
       print(f"❌ 读取文件失败：{e}")
        return 0, []

def compare_results(local_count, local_urls, local_time, online_count, online_urls):
    """对比结果"""
   print("\n" + "=" * 60)
   print("对比分析")
   print("=" * 60)
    
   print(f"\n📊 统计数据:")
   print(f"  本地项目：{local_count} 个 URL (耗时：{local_time:.2f}秒)")
   print(f"  在线平台：{online_count} 个 URL")
    
   if local_count > 0 and online_count > 0:
        difference = online_count - local_count
        percentage = (local_count / online_count * 100) if online_count > 0 else 0
        
       print(f"\n📈 差异分析:")
       print(f"  数量差异：{difference:+d} 个 URL")
       print(f"  覆盖率：{percentage:.1f}%")
        
       if difference < 0:
           print(f"  ⚠️  本地项目比在线平台少 {abs(difference)} 个 URL")
        elif difference > 0:
           print(f"  ✅ 本地项目比在线平台多 {difference} 个 URL")
        else:
           print(f"  ✅ 两者结果完全一致!")
        
        # 找出 URL 差异
       if local_urls and online_urls:
            local_set = set(local_urls)
            online_set = set(online_urls)
            
            missing_in_local = online_set - local_set
            extra_in_local = local_set - online_set
            
           print(f"\n🔍 URL 差异:")
           print(f"  本地缺失的 URL: {len(missing_in_local)} 个")
           print(f"  本地额外的 URL: {len(extra_in_local)} 个")
            
           if missing_in_local:
               print("\n  本地缺失的前 10 个 URL:")
                for i, url in enumerate(list(missing_in_local)[:10], 1):
                   print(f"  {i}. {url}")
            
           if extra_in_local:
               print("\n  本地额外的前 10 个 URL:")
                for i, url in enumerate(list(extra_in_local)[:10], 1):
                   print(f"  {i}. {url}")
        
        # 优化建议
       print("\n" + "=" * 60)
       print("优化建议")
       print("=" * 60)
        
       if percentage < 80:
           print("⚠️  覆盖率较低，建议以下优化:")
           print("  1. 增加爬取深度 (当前深度=5)")
           print("  2. 增加最大页面数限制 (当前 MAX_PAGES=500)")
           print("  3. 减少 URL 过滤规则，放宽爬取限制")
           print("  4. 增加超时时间 (当前 TIMEOUT_MS=15000)")
           print("  5. 优化并发线程数 (当前 MAX_THREADS=10)")
        elif percentage < 95:
           print("✅ 覆盖率良好，仍有优化空间:")
           print("  1. 检查是否有特定域名被过滤")
           print("  2. 调整分页识别规则")
           print("  3. 优化 User-Agent 设置")
        else:
           print("✅ 优秀！与在线平台结果非常接近")

def main():
    """主函数"""
   print("\n" + "=" * 60)
   print("站点地图生成器对比测试")
   print("测试 URL: http://www.hnzwgs.com/")
   print("=" * 60)
    
    # 测试本地项目
    local_count, local_urls, local_time = test_local_sitemap()
    
    # 测试在线平台
    online_count, online_urls = test_xml_sitemaps_com()
    
    # 对比结果
   if local_count > 0 and online_count > 0:
        compare_results(local_count, local_urls, local_time, online_count, online_urls)
    else:
       print("\n❌ 无法对比：缺少有效数据")
    
   print("\n测试完成!")

if __name__ == "__main__":
    main()
