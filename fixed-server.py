#!/usr/bin/env python3
"""
修复版的站点地图生成器服务器
包含完整的下载和预览功能
"""

import http.server
import socketserver
import urllib.parse
import json
import os
import time
from datetime import datetime
import urllib.request
from urllib.parse import urlparse
import html

PORT = 8080

class SitemapHandler(http.server.BaseHTTPRequestHandler):
    
    def do_GET(self):
        """处理GET请求"""
        parsed_path = urllib.parse.urlparse(self.path)
        path = parsed_path.path
        
        print(f"收到请求: {self.path}")
        
        if path == '/':
            self.send_home_page()
        elif path == '/generate':
            self.send_generate_form()
        elif path == '/download':
            self.handle_download()
        elif path == '/preview':
            self.handle_preview()
        elif path == '/about':
            self.send_about_page()
        elif path == '/help':
            self.send_help_page()
        elif path == '/api/health':
            self.send_json({'status': 'ok', 'timestamp': datetime.now().isoformat()})
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
        html_content = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>站点地图生成器</title>
            <style>
                body {
                    font-family: Arial, sans-serif;
                    max-width: 800px;
                    margin: 0 auto;
                    padding: 20px;
                    background: #f5f5f5;
                }
                .container {
                    background: white;
                    padding: 30px;
                    border-radius: 10px;
                    box-shadow: 0 2px 10px rgba(0,0,0,0.1);
                }
                h1 {
                    color: #333;
                    border-bottom: 2px solid #4CAF50;
                    padding-bottom: 10px;
                }
                .form-group {
                    margin: 20px 0;
                }
                label {
                    display: block;
                    margin-bottom: 5px;
                    font-weight: bold;
                }
                input[type="url"] {
                    width: 100%;
                    padding: 10px;
                    border: 1px solid #ddd;
                    border-radius: 5px;
                    font-size: 16px;
                }
                .checkbox-group {
                    margin: 15px 0;
                }
                .checkbox-group label {
                    display: inline-block;
                    margin-right: 20px;
                    font-weight: normal;
                }
                button {
                    background: #4CAF50;
                    color: white;
                    border: none;
                    padding: 12px 24px;
                    font-size: 16px;
                    border-radius: 5px;
                    cursor: pointer;
                    margin-right: 10px;
                }
                button:hover {
                    background: #45a049;
                }
                .result {
                    margin-top: 20px;
                    padding: 15px;
                    background: #f8f9fa;
                    border-radius: 5px;
                    border: 1px solid #dee2e6;
                    display: none;
                }
                .result.show {
                    display: block;
                }
                pre {
                    background: #282c34;
                    color: #abb2bf;
                    padding: 15px;
                    border-radius: 5px;
                    overflow-x: auto;
                    white-space: pre-wrap;
                    word-wrap: break-word;
                    font-family: 'Courier New', monospace;
                    font-size: 14px;
                }
                .alert {
                    padding: 15px;
                    margin: 20px 0;
                    border-radius: 5px;
                }
                .alert-success {
                    background: #d4edda;
                    color: #155724;
                    border: 1px solid #c3e6cb;
                }
                .alert-error {
                    background: #f8d7da;
                    color: #721c24;
                    border: 1px solid #f5c6cb;
                }
                .server-info {
                    margin-top: 30px;
                    padding: 15px;
                    background: #e9ecef;
                    border-radius: 5px;
                    font-size: 14px;
                }
                .actions {
                    margin: 20px 0;
                }
                .download-btn {
                    background: #2196F3;
                }
                .preview-btn {
                    background: #FF9800;
                }
            </style>
        </head>
        <body>
            <div class="container">
                <h1>🚀 站点地图生成器</h1>
                <p>输入网址，一键生成站点地图XML文件</p>
                
                <form id="sitemapForm" method="POST" action="/generate">
                    <div class="form-group">
                        <label for="url">网站地址：</label>
                        <input type="url" id="url" name="url" 
                               placeholder="https://example.com" 
                               required
                               value="https://www.baidu.com">
                    </div>
                    
                    <div class="checkbox-group">
                        <label>
                            <input type="checkbox" name="includeImages" value="true">
                            包含图片站点地图
                        </label>
                        <label>
                            <input type="checkbox" name="includeVideos" value="true">
                            包含视频站点地图
                        </label>
                    </div>
                    
                    <div class="form-group actions">
                        <button type="submit">生成站点地图</button>
                        <button type="button" onclick="testGenerate()">测试生成</button>
                        <button type="button" class="download-btn" onclick="testDownload()">测试下载</button>
                        <button type="button" class="preview-btn" onclick="testPreview()">测试预览</button>
                    </div>
                </form>
                
                <div id="result" class="result"></div>
                
                <div class="server-info">
                    <h3>服务器信息</h3>
                    <p><strong>服务器：</strong>Python HTTP Server</p>
                    <p><strong>端口：</strong>8080</p>
                    <p><strong>状态：</strong>运行中</p>
                    <p><strong>时间：</strong>""" + datetime.now().strftime("%Y-%m-%d %H:%M:%S") + """</p>
                </div>
            </div>
            
            <script>
                function testGenerate() {
                    document.getElementById('url').value = 'https://www.baidu.com';
                    document.getElementById('sitemapForm').submit();
                }
                
                function testDownload() {
                    const url = document.getElementById('url').value || 'https://www.baidu.com';
                    window.open('/download?url=' + encodeURIComponent(url) + '&includeImages=false&includeVideos=false', '_blank');
                }
                
                function testPreview() {
                    const url = document.getElementById('url').value || 'https://www.baidu.com';
                    window.open('/preview?url=' + encodeURIComponent(url) + '&includeImages=false&includeVideos=false', '_blank');
                }
                
                // 显示加载状态
                document.getElementById('sitemapForm').addEventListener('submit', function() {
                    const resultDiv = document.getElementById('result');
                    resultDiv.className = 'result show';
                    resultDiv.innerHTML = '<div class="alert">正在生成站点地图，请稍候...</div>';
                });
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
        include_images = 'includeImages' in params
        include_videos = 'includeVideos' in params
        
        if not url:
            self.send_error(400, "请输入网址")
            return
        
        try:
            # 生成简单的站点地图XML
            sitemap_xml = self.generate_simple_sitemap(url, include_images, include_videos)
            
            # 生成结果页面
            html_content = f"""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>站点地图生成结果</title>
                <style>
                    body {{ font-family: Arial, sans-serif; margin: 20px; }}
                    .container {{ max-width: 1000px; margin: 0 auto; }}
                    .success {{ background: #d4edda; color: #155724; padding: 15px; border-radius: 5px; margin: 20px 0; }}
                    pre {{ background: #282c34; color: #abb2bf; padding: 20px; border-radius: 5px; overflow-x: auto; }}
                    .actions {{ margin: 20px 0; }}
                    button {{ background: #4CAF50; color: white; border: none; padding: 10px 20px; margin-right: 10px; cursor: pointer; }}
                    button:hover {{ background: #45a049; }}
                    .download-btn {{ background: #2196F3; }}
                    .preview-btn {{ background: #FF9800; }}
                    .back-btn {{ background: #6c757d; }}
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>✅ 站点地图生成成功！</h1>
                    
                    <div class="success">
                        <strong>网站：</strong>{html.escape(url)}<br>
                        <strong>生成时间：</strong>{datetime.now().strftime("%Y-%m-%d %H:%M:%S")}<br>
                        <strong>文件大小：</strong>{len(sitemap_xml)} 字节<br>
                        <strong>包含图片：</strong>{'是' if include_images else '否'}<br>
                        <strong>包含视频：</strong>{'是' if include_videos else '否'}
                    </div>
                    
                    <div class="actions">
                        <button onclick="downloadSitemap()" class="download-btn">📥 下载XML文件</button>
                        <button onclick="previewSitemap()" class="preview-btn">👁️ 预览XML</button>
                        <button onclick="copyToClipboard()">📋 复制XML</button>
                        <button onclick="window.location.href='/'" class="back-btn">🔄 重新生成</button>
                    </div>
                    
                    <h3>站点地图XML预览：</h3>
                    <pre id="xmlContent">{html.escape(sitemap_xml)}</pre>
                </div>
                
                <script>
                    function downloadSitemap() {{
                        const xmlContent = document.getElementById('xmlContent').textContent;
                        const blob = new Blob([xmlContent], {{ type: 'application/xml' }});
                        const url = URL.createObjectURL(blob);
                        const a = document.createElement('a');
                        a.href = url;
                        a.download = 'sitemap-{self.get_domain(url)}.xml';
                        document.body.appendChild(a);
                        a.click();
                        document.body.removeChild(a);
                        URL.revokeObjectURL(url);
                    }}
                    
                    function previewSitemap() {{
                        const url = '{html.escape(url)}';
                        window.open('/preview?url=' + encodeURIComponent(url) + '&includeImages={'true' if include_images else 'false'}&includeVideos={'true' if include_videos else 'false'}', '_blank');
                    }}
                    
                    function copyToClipboard() {{
                        const xmlContent = document.getElementById('xmlContent').textContent;
                        navigator.clipboard.writeText(xmlContent).then(() => {{
                            alert('XML内容已复制到剪贴板！');
                        }});
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
    
    def generate_simple_sitemap(self, url, include_images=False, include_videos=False):
        """生成简单的站点地图XML"""
        domain = self.get_domain(url)
        now = datetime.now().strftime("%Y-%m-%d")
        
        xml = '<?xml version="1.0" encoding="UTF-8"?>\n'
        xml += '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"\n'
        
        if include_images:
            xml += '       xmlns:image="http://www.google.com/schemas/sitemap-image/1.1"\n'
        
        if include_videos:
            xml += '       xmlns:video="http://www.google.com/schemas/sitemap-video/1.1"\n'
        
        xml += '>\n'
        
        # 生成一些示例URL
        urls = [
            url,
            f"{url.rstrip('/')}/about",
            f"{url.rstrip('/')}/contact",
            f"{url.rstrip('/')}/products",
            f"{url.rstrip('/')}/services",
            f"{url.rstrip('/')}/blog",
            f"{url.rstrip('/')}/faq",
            f"{url.rstrip('/')}/privacy",
            f"{url.rstrip('/')}/terms",
        ]
        
        for url_item in urls:
            xml += f'  <url>\n'
            xml += f'    <loc>{html.escape(url_item)}</loc>\n'
            xml += f'    <lastmod>{now}</lastmod>\n'
            xml += f'    <changefreq>weekly</changefreq>\n'
            xml += f'    <priority>0.8</priority>\n'
            
            # 如果包含图片，添加图片信息
            if include_images:
                xml += f'    <image:image>\n'
                xml += f'      <image:loc>{html.escape(url_item)}/image.jpg</image:loc>\n'
                xml += f'      <image:title>示例图片</image:title>\n'
                xml += f'    </image:image>\n'
            
            # 如果包含视频，添加视频信息
            if include_videos:
                xml += f'    <video:video>\n'
                xml += f'      <video:thumbnail_loc>{html.escape(url_item)}/thumbnail.jpg</video:thumbnail_loc>\n'
                xml += f'      <video:title>示例视频</video:title>\n'
                xml += f'      <video:description>这是一个示例视频描述</video:description>\n'
                xml += f'    </video:video>\n'
            
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
            # 替换特殊字符
            domain = domain.replace('.', '-').replace(':', '-')
            return domain
        except:
            return "website"
    
    def handle_download(self):
        """处理下载请求"""
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        url = query.get('url', [''])[0]
        include_images = query.get('includeImages', ['false'])[0] == 'true'
        include_videos = query.get('includeVideos', ['false'])[0] == 'true'
        
        print(f"下载请求 - URL: {url}, 图片: {include_images}, 视频: {include_videos}")
        
        if not url:
            self.send_error(400, "请输入网址")
            return
        
        try:
            sitemap_xml = self.generate_simple_sitemap(url, include_images, include_videos)
            domain = self.get_domain(url)
            filename = f"sitemap-{domain}.xml"
            
            self.send_response(200)
            self.send_header('Content-type', 'application/xml')
            self.send_header('Content-Disposition', f'attachment; filename="{filename}"')
            self.send_header('Content-Length', str(len(sitemap_xml)))
            self.end_headers()
            self.wfile.write(sitemap_xml.encode('utf-8'))
            
            print(f"下载成功: {filename}, 大小: {len(sitemap_xml)} 字节")
            
        except Exception as e:
            print(f"下载失败: {str(e)}")
            self.send_error(500, f"下载失败: {str(e)}")
    
    def handle_preview(self):
        """处理预览请求"""
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        url = query.get('url', [''])[0]
        include_images = query.get('includeImages', ['false'])[0] == 'true'
        include_videos = query.get('includeVideos', ['false'])[0] == 'true'
        
        print(f"预览请求 - URL: {url}, 图片: {include_images}, 视频: {include_videos}")
        
        if not url:
            self.send_error(400, "请输入网址")
            return
        
        try:
            sitemap_xml = self.generate_simple_sitemap(url, include