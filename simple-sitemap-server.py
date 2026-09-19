#!/usr/bin/env python3
"""
简单的站点地图生成器服务器 - 修复版
"""

from http.server import HTTPServer, BaseHTTPRequestHandler
import urllib.parse
from datetime import datetime
from urllib.parse import urlparse
import html

PORT = 8088

class SitemapHandler(BaseHTTPRequestHandler):
    
    def do_GET(self):
        """处理GET请求"""
        path = urllib.parse.urlparse(self.path).path
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        
        print(f"GET请求: {self.path}")
        
        if path == '/':
            self.send_home_page()
        elif path == '/download':
            self.handle_download(query)
        elif path == '/preview':
            self.handle_preview(query)
        elif path == '/health':
            self.send_json({'status': 'ok', 'time': datetime.now().isoformat()})
        else:
            self.send_error(404, "页面不存在")
    
    def do_POST(self):
        """处理POST请求"""
        if self.path == '/generate':
            self.handle_generate()
        else:
            self.send_error(404, "API不存在")
    
    def send_home_page(self):
        """发送首页"""
        html_content = f"""
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <title>站点地图生成器</title>
            <style>
                body {{ font-family: Arial, sans-serif; margin: 40px; }}
                .container {{ max-width: 800px; margin: 0 auto; }}
                h1 {{ color: #333; }}
                input {{ width: 100%; padding: 10px; margin: 10px 0; }}
                button {{ background: #4CAF50; color: white; padding: 10px 20px; border: none; cursor: pointer; margin: 5px; }}
                .download {{ background: #2196F3; }}
                .preview {{ background: #FF9800; }}
                pre {{ background: #f5f5f5; padding: 15px; border-radius: 5px; overflow-x: auto; }}
            </style>
        </head>
        <body>
            <div class="container">
                <h1>🚀 站点地图生成器</h1>
                <p>输入网址，生成站点地图XML文件</p>
                
                <form method="POST" action="/generate">
                    <input type="url" name="url" placeholder="https://example.com" value="https://www.baidu.com" required>
                    <div>
                        <label><input type="checkbox" name="images"> 包含图片</label>
                        <label><input type="checkbox" name="videos"> 包含视频</label>
                    </div>
                    <button type="submit">生成站点地图</button>
                </form>
                
                <div style="margin-top: 30px;">
                    <h3>测试功能：</h3>
                    <button onclick="testDownload()" class="download">测试下载</button>
                    <button onclick="testPreview()" class="preview">测试预览</button>
                </div>
                
                <div style="margin-top: 30px; padding: 15px; background: #e9ecef; border-radius: 5px;">
                    <p><strong>服务器状态：</strong>运行中</p>
                    <p><strong>访问地址：</strong>http://localhost:{PORT}</p>
                    <p><strong>当前时间：</strong>{datetime.now().strftime("%Y-%m-%d %H:%M:%S")}</p>
                </div>
            </div>
            
            <script>
                function testDownload() {{
                    const url = document.querySelector('input[name="url"]').value || 'https://www.baidu.com';
                    window.open('/download?url=' + encodeURIComponent(url), '_blank');
                }}
                
                function testPreview() {{
                    const url = document.querySelector('input[name="url"]').value || 'https://www.baidu.com';
                    window.open('/preview?url=' + encodeURIComponent(url), '_blank');
                }}
            </script>
        </body>
        </html>
        """
        
        self.send_response(200)
        self.send_header('Content-type', 'text/html; charset=utf-8')
        self.end_headers()
        self.wfile.write(html_content.encode('utf-8'))
    
    def handle_generate(self):
        """处理生成请求"""
        content_length = int(self.headers['Content-Length'])
        post_data = self.rfile.read(content_length).decode('utf-8')
        params = urllib.parse.parse_qs(post_data)
        
        url = params.get('url', [''])[0]
        
        if not url:
            self.send_error(400, "请输入网址")
            return
        
        try:
            sitemap_xml = self.generate_sitemap(url)
            
            html_content = f"""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>生成成功</title>
                <style>
                    body {{ font-family: Arial, sans-serif; margin: 40px; }}
                    .success {{ background: #d4edda; padding: 15px; border-radius: 5px; margin: 20px 0; }}
                    button {{ background: #4CAF50; color: white; padding: 10px 20px; border: none; cursor: pointer; margin: 5px; }}
                    .download {{ background: #2196F3; }}
                    .preview {{ background: #FF9800; }}
                    pre {{ background: #282c34; color: white; padding: 15px; border-radius: 5px; overflow-x: auto; }}
                </style>
            </head>
            <body>
                <h1>✅ 生成成功！</h1>
                
                <div class="success">
                    <p><strong>网站：</strong>{html.escape(url)}</p>
                    <p><strong>时间：</strong>{datetime.now().strftime("%Y-%m-%d %H:%M:%S")}</p>
                    <p><strong>大小：</strong>{len(sitemap_xml)} 字节</p>
                </div>
                
                <div>
                    <button onclick="downloadFile()" class="download">📥 下载XML</button>
                    <button onclick="previewFile()" class="preview">👁️ 预览</button>
                    <button onclick="window.location.href='/'">🔄 重新生成</button>
                </div>
                
                <h3>XML预览：</h3>
                <pre id="xml">{html.escape(sitemap_xml)}</pre>
                
                <script>
                    function downloadFile() {{
                        const url = '{html.escape(url)}';
                        window.open('/download?url=' + encodeURIComponent(url), '_blank');
                    }}
                    
                    function previewFile() {{
                        const url = '{html.escape(url)}';
                        window.open('/preview?url=' + encodeURIComponent(url), '_blank');
                    }}
                </script>
            </body>
            </html>
            """
            
            self.send_response(200)
            self.send_header('Content-type', 'text/html; charset=utf-8')
            self.end_headers()
            self.wfile.write(html_content.encode('utf-8'))
            
        except Exception as e:
            self.send_error(500, f"生成失败: {str(e)}")
    
    def handle_download(self, query):
        """处理下载请求"""
        url = query.get('url', [''])[0]
        
        if not url:
            self.send_error(400, "请输入网址")
            return
        
        try:
            sitemap_xml = self.generate_sitemap(url)
            domain = self.get_domain(url)
            filename = f"sitemap-{domain}.xml"
            
            self.send_response(200)
            self.send_header('Content-type', 'application/xml')
            self.send_header('Content-Disposition', f'attachment; filename="{filename}"')
            self.send_header('Content-Length', str(len(sitemap_xml)))
            self.end_headers()
            self.wfile.write(sitemap_xml.encode('utf-8'))
            
            print(f"下载成功: {filename}")
            
        except Exception as e:
            print(f"下载失败: {str(e)}")
            self.send_error(500, f"下载失败: {str(e)}")
    
    def handle_preview(self, query):
        """处理预览请求"""
        url = query.get('url', [''])[0]
        
        if not url:
            self.send_error(400, "请输入网址")
            return
        
        try:
            sitemap_xml = self.generate_sitemap(url)
            
            html_content = f"""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>站点地图预览 - {html.escape(url)}</title>
                <style>
                    body {{ font-family: Arial, sans-serif; margin: 20px; }}
                    pre {{ background: #282c34; color: white; padding: 20px; border-radius: 5px; overflow-x: auto; }}
                    button {{ background: #6c757d; color: white; padding: 10px 20px; border: none; cursor: pointer; margin: 10px; }}
                </style>
            </head>
            <body>
                <h1>站点地图预览</h1>
                <p><strong>网站：</strong>{html.escape(url)}</p>
                <p><strong>生成时间：</strong>{datetime.now().strftime("%Y-%m-%d %H:%M:%S")}</p>
                
                <button onclick="window.history.back()">返回</button>
                <button onclick="window.location.href='/download?url=' + encodeURIComponent('{html.escape(url)}')">下载</button>
                
                <pre>{html.escape(sitemap_xml)}</pre>
            </body>
            </html>
            """
            
            self.send_response(200)
            self.send_header('Content-type', 'text/html; charset=utf-8')
            self.end_headers()
            self.wfile.write(html_content.encode('utf-8'))
            
        except Exception as e:
            self.send_error(500, f"预览失败: {str(e)}")
    
    def generate_sitemap(self, url):
        """生成站点地图XML"""
        now = datetime.now().strftime("%Y-%m-%d")
        
        xml = '<?xml version="1.0" encoding="UTF-8"?>\n'
        xml += '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
        
        # 生成示例URL
        urls = [
            url,
            f"{url.rstrip('/')}/about",
            f"{url.rstrip('/')}/contact",
            f"{url.rstrip('/')}/products",
            f"{url.rstrip('/')}/services",
            f"{url.rstrip('/')}/blog",
        ]
        
        for url_item in urls:
            xml += f'  <url>\n'
            xml += f'    <loc>{html.escape(url_item)}</loc>\n'
            xml += f'    <lastmod>{now}</lastmod>\n'
            xml += f'    <changefreq>weekly</changefreq>\n'
            xml += f'    <priority>0.8</priority>\n'
            xml += f'  </url>\n'
        
        xml += '</urlset>'
        return xml
    
    def get_domain(self, url):
        """从URL提取域名"""
        try:
            parsed = urlparse(url)
            domain = parsed.netloc
            if domain.startswith('www.'):
                domain = domain[4:]
            domain = domain.replace('.', '-').replace(':', '-')
            return domain[:50]  # 限制长度
        except:
            return "website"
    
    def send_json(self, data):
        """发送JSON响应"""
        self.send_response(200)
        self.send_header('Content-type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(data).encode('utf-8'))
    
    def log_message(self, format, *args):
        """自定义日志格式"""
        print(f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] {format % args}")

# 启动服务器
if __name__ == '__main__':
    import json
    
    server = HTTPServer(('localhost', PORT), SitemapHandler)
    print(f"🚀 站点地图生成器已启动!")
    print(f"📌 访问地址: http://localhost:{PORT}")
    print(f"⏰ 启动时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 50)
    print("按 Ctrl+C 停止服务器")
    
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n🛑 服务器已停止")
    except Exception as e:
        print(f"❌ 服务器错误: {e}")