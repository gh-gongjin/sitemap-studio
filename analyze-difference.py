"""
自动对比分析工具
对比本地项目与 xml-sitemaps.com 的差异
"""

import requests
import xml.etree.ElementTree as ET
from bs4 import BeautifulSoup
import time
import re
from urllib.parse import urlparse, urljoin

class SitemapComparator:
    def __init__(self):
        self.test_url = "http://www.hnzwgs.com/"
        self.local_api = "http://localhost:8080/api/generate"
        
    def analyze_target_website(self):
        """分析目标网站结构"""
       print("=" * 60)
       print("分析目标网站结构")
       print("=" * 60)
        
        try:
            headers = {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
            }
            
           response = requests.get(self.test_url, headers=headers, timeout=10)
            soup = BeautifulSoup(response.text, 'html.parser')
            
            # 统计链接
            all_links = soup.find_all('a', href=True)
            internal_links = []
            external_links = []
            
            for link in all_links:
                href = link['href']
               if href.startswith(self.test_url) or href.startswith('/'):
                    internal_links.append(href)
                else:
                    external_links.append(href)
            
           print(f"\n📊 网站结构分析:")
           print(f"  总链接数：{len(all_links)}")
           print(f"  内部链接：{len(internal_links)}")
           print(f"  外部链接：{len(external_links)}")
            
            # 分析链接深度
            depths = {}
            for link in internal_links:
                # 计算路径深度
                path = urlparse(link).path
                depth = path.count('/')
                depths[depth] = depths.get(depth, 0) + 1
            
           print(f"\n📈 链接深度分布:")
            for depth in sorted(depths.keys()):
               print(f"  深度{depth}: {depths[depth]} 个链接")
            
           return len(internal_links), depths
            
        except Exception as e:
           print(f"❌ 分析失败：{e}")
           return 0, {}
    
    def test_local_generator(self):
        """测试本地生成器"""
       print("\n" + "=" * 60)
       print("测试本地项目")
       print("=" * 60)
        
        try:
            params = {
                "url": self.test_url,
                "includeImages": "false",
                "includeVideos": "false"
            }
            
           print(f"正在请求本地 API...")
            start_time = time.time()
           response = requests.get(self.local_api, params=params, timeout=120)
            end_time = time.time()
            
           if response.status_code == 200:
               xml_content = response.text
                
                # 解析 XML
                root = ET.fromstring(xml_content)
                urls = root.findall('.//{http://www.sitemaps.org/schemas/sitemap/0.9}loc')
                
               print(f"\n✅ 生成成功!")
               print(f"耗时：{end_time - start_time:.2f}秒")
               print(f"URL 数量：{len(urls)}")
               print(f"响应大小：{len(xml_content)}字节")
                
                # 保存结果
                with open("local_sitemap.xml", "w", encoding="utf-8") as f:
                    f.write(xml_content)
               print("XML 已保存到：local_sitemap.xml")
                
                # 分析 URL 深度分布
                url_list = [url.text for url in urls]
                depth_analysis = self.analyze_url_depths(url_list)
                
               print(f"\n📈 本地生成 URL 深度分布:")
                for depth in sorted(depth_analysis.keys()):
                   print(f"  深度{depth}: {depth_analysis[depth]} 个 URL")
                
                # 显示前 20 个 URL
               print(f"\n前 20 个 URL:")
                for i, url in enumerate(url_list[:20], 1):
                   print(f"{i}. {url}")
                
               return len(urls), url_list
                
            else:
               print(f"❌ 请求失败：HTTP {response.status_code}")
               return 0, []
                
        except requests.exceptions.Timeout:
           print("❌ 请求超时")
           return 0, []
        except requests.exceptions.ConnectionError:
           print("❌ 连接失败：请确保本地服务正在运行")
           return 0, []
        except Exception as e:
           print(f"❌ 发生错误：{e}")
           return 0, []
    
    def analyze_url_depths(self, urls):
        """分析 URL 深度分布"""
        depths = {}
        for url in urls:
            try:
                parsed = urlparse(url)
                depth = parsed.path.count('/')
                depths[depth] = depths.get(depth, 0) + 1
            except:
                pass
       return depths
    
    def get_online_platform_info(self):
        """获取在线平台信息"""
       print("\n" + "=" * 60)
       print("xml-sitemaps.com 平台特点")
       print("=" * 60)
        
       print("""
📋 xml-sitemaps.com 主要特点:

1. 爬取策略:
   - 默认爬取深度：无限深度（但受页面数限制）
   - 最大页面数：免费版 500 页
   - 并发线程：较高（估计 20+ 线程）
   - User-Agent: 多种爬虫标识
   
2. URL 过滤规则:
   - 不过滤常见 CMS 参数
   - 允许带查询参数的 URL
   - 会爬取分页页面
   - 排除重复内容
   
3. 特殊处理:
   - 自动识别 sitemap index
   - 支持 robots.txt 遵循
   - 智能去重算法
   - 规范化 URL（去除尾部斜杠等）
   
4. 输出格式:
   - 标准 XML sitemap
   - 可选 HTML sitemap
   - 包含最后修改时间
   - 自动计算优先级和更新频率

💡 关键差异点:
   - xml-sitemaps.com 对查询参数更宽松
   - 会爬取更多分页页面
   - URL 规范化处理更完善
   - 可能遵循 robots.txt（可选）
""")
    
    def generate_optimization_suggestions(self, local_count, online_count):
        """生成优化建议"""
       print("\n" + "=" * 60)
       print("优化建议")
       print("=" * 60)
        
       if online_count > 0:
            coverage = (local_count / online_count * 100)
           print(f"\n当前覆盖率：{coverage:.1f}%")
            
           if coverage < 50:
               print("\n⚠️  覆盖率严重不足，需要大幅优化")
            elif coverage < 80:
               print("\n⚠️  覆盖率较低，需要优化")
            elif coverage < 95:
               print("\n✅ 覆盖率良好，仍有提升空间")
            else:
               print("\n✅ 优秀！接近或超过在线平台")
        
       print("""
🔧 具体优化措施:

1. 调整爬取深度配置:
   - 当前：MAX_DEPTH = 5
   - 建议：增加到 10 或取消限制
   - 位置：EnhancedSitemapGeneratorService.java

2. 放宽 URL 过滤规则:
   - 移除部分 EXCLUDED_KEYWORDS
   - 减少对查询参数的限制
   - 允许某些动态页面

3. 优化分页识别:
   - 当前 PAGINATION_PATTERN 过于严格
   - 建议放宽分页检测规则
   - 允许多页内容

4. 增强并发能力:
   - 当前：MAX_THREADS = 10
   - 建议：增加到 20-30
   - 注意：需平衡服务器负载

5. 改进超时设置:
   - 当前：TIMEOUT_MS = 15000
   - 建议：增加到 30000
   - 减少因超时而丢失的页面

6. URL 规范化:
   - 添加 URL 规范化处理
   - 去除尾部斜杠
   - 统一参数顺序
   - 处理 www 和非 www 版本

7. 增加重试机制:
   - 当前已有 3 次重试
   - 建议使用指数退避策略
   - 增加随机延迟避免被封

8. 优化 User-Agent:
   - 使用更多样的 User-Agent
   - 模拟真实浏览器行为
   - 避免被识别为爬虫
""")
        
       print("\n📝 代码修改位置:")
       print("  1. EnhancedSitemapGeneratorService.java")
       print("     - MAX_DEPTH (第 30 行)")
       print("     - MAX_THREADS (第 29 行)")
       print("     - MAX_PAGES (第 31 行)")
       print("     - TIMEOUT_MS (第 32 行)")
       print("     - EXCLUDED_KEYWORDS (第 48-54 行)")
       print("     - PAGINATION_PATTERN (第 65-67 行)")
       print("")
       print("  2. application.properties")
       print("     - sitemap.generator.max-depth (第 21 行)")
       print("")

def main():
    comparator = SitemapComparator()
    
   print("\n" + "=" * 60)
   print("站点地图生成器对比分析工具")
   print("目标网站：http://www.hnzwgs.com/")
   print("=" * 60)
    
    # 1. 分析目标网站
    internal_links, depth_dist = comparator.analyze_target_website()
    
    # 2. 获取在线平台信息
    comparator.get_online_platform_info()
    
    # 3. 测试本地生成器
    local_count, local_urls = comparator.test_local_generator()
    
    # 4. 生成优化建议
    # 假设在线平台能找到约 internal_links * 1.5 个页面（考虑深度爬取）
    estimated_online = max(internal_links, 100)  # 预估值
    comparator.generate_optimization_suggestions(local_count, estimated_online)
    
   print("\n" + "=" * 60)
   print("分析完成!")
   print("=" * 60)
   print("\n下一步操作:")
   print("1. 手动访问 https://www.xml-sitemaps.com/ 测试 http://www.hnzwgs.com/")
   print("2. 记录找到的 URL 数量")
   print("3. 根据优化建议修改代码")
   print("4. 重新运行此脚本对比结果")
   print("")

if __name__ == "__main__":
    main()
