// 从响应中提取统计信息
function extractStatsFromResponse(html) {
    const parser = new DOMParser();
    const doc = parser.parseFromString(html, 'text/html');
    
    const stats = {
        urlCount: 0,
        fileSize: '未知',
        pages: [],
        stats: {}
    };
    
    // 尝试从页面提取信息
    const successDiv = doc.querySelector('.success');
    if (successDiv) {
        const text = successDiv.textContent;
        
        // 提取URL数量
        const urlMatch = text.match(/(\d+)\s*个链接/);
        if (urlMatch) {
            stats.urlCount = parseInt(urlMatch[1]);
        }
        
        // 提取文件大小
        const sizeMatch = text.match(/(\d+(\.\d+)?)\s*(B|KB|MB)/);
        if (sizeMatch) {
            stats.fileSize = sizeMatch[0];
        }
    }
    
    // 提取页面URL（从XML预览）
    const xmlContent = doc.querySelector('code.language-xml');
    if (xmlContent) {
        const xmlText = xmlContent.textContent;
        const urlMatches = xmlText.match(/<loc>(https?:\/\/[^<]+)<\/loc>/g);
        if (urlMatches) {
            stats.pages = urlMatches.map(match => 
                match.replace('<loc>', '').replace('</loc>', '')
            );
            stats.urlCount = stats.pages.length;
        }
    }
    
    return stats;
}

// 显示我们的结果
function displayOurResults(results) {
    let html = '<h4>生成结果：</h4>';
    
    if (results.urlCount > 0) {
        html += `<p><strong>URL数量：</strong> ${results.urlCount} 个</p>`;
        html += `<p><strong>文件大小：</strong> ${results.fileSize}</p>`;
        
        if (results.pages && results.pages.length > 0) {
            html += '<h5>发现的页面：</h5>';
            html += '<ul style="max-height: 200px; overflow-y: auto;">';
            results.pages.slice(0, 10).forEach(page => {
                html += `<li><a href="${page}" target="_blank">${page}</a></li>`;
            });
            if (results.pages.length > 10) {
                html += `<li>... 还有 ${results.pages.length - 10} 个页面</li>`;
            }
            html += '</ul>';
        }
    } else {
        html += '<p>❌ 未找到任何页面</p>';
    }
    
    document.getElementById('ourResult').innerHTML = html;
    document.getElementById('ourResult').className = 'result show';
}

// 显示在线平台结果
function displayOnlineResults(results) {
    let html = '<h4>在线平台结果（模拟）：</h4>';
    
    html += `<p><strong>URL数量：</strong> ${results.urlCount} 个</p>`;
    html += `<p><strong>文件大小：</strong> ${results.fileSize}</p>`;
    
    if (results.stats) {
        html += '<h5>网站信息：</h5>';
        html += `<p><strong>域名：</strong> ${results.stats.domain}</p>`;
        html += `<p><strong>标题：</strong> ${results.stats.title}</p>`;
        html += `<p><strong>描述：</strong> ${results.stats.description}</p>`;
    }
    
    if (results.pages && results.pages.length > 0) {
        html += '<h5>发现的页面：</h5>';
        html += '<ul style="max-height: 200px; overflow-y: auto;">';
        results.pages.slice(0, 10).forEach(page => {
            html += `<li><a href="${page}" target="_blank">${page}</a></li>`;
        });
        if (results.pages.length > 10) {
            html += `<li>... 还有 ${results.pages.length - 10} 个页面</li>`;
        }
        html += '</ul>';
    }
    
    document.getElementById('onlineResult').innerHTML = html;
    document.getElementById('onlineResult').className = 'result show';
}

// 更新统计信息
function updateStats() {
    const statsContainer = document.getElementById('statsContainer');
    let html = '';
    
    if (ourResults) {
        html += `
            <div class="stat-item">
                <div class="stat-value">${ourResults.urlCount || 0}</div>
                <div class="stat-label">我们的URL数量</div>
            </div>
        `;
    }
    
    if (onlineResults) {
        html += `
            <div class="stat-item">
                <div class="stat-value">${onlineResults.urlCount || 0}</div>
                <div class="stat-label">在线平台URL数量</div>
            </div>
        `;
    }
    
    if (ourResults && onlineResults) {
        const difference = (ourResults.urlCount || 0) - (onlineResults.urlCount || 0);
        const diffClass = difference < 0 ? 'error' : difference > 0 ? 'success' : 'info';
        const diffText = difference < 0 ? `少 ${Math.abs(difference)}` : 
                        difference > 0 ? `多 ${difference}` : '相同';
        
        html += `
            <div class="stat-item">
                <div class="stat-value" style="color: ${difference < 0 ? '#f44336' : difference > 0 ? '#4CAF50' : '#2196F3'}">
                    ${diffText}
                </div>
                <div class="stat-label">差异</div>
            </div>
        `;
    }
    
    statsContainer.innerHTML = html;
}

// 应用修复
function applyFixes() {
    showResult('differenceResult', '正在应用修复...', 'info');
    
    // 模拟应用修复
    setTimeout(() => {
        showResult('differenceResult', '✅ 修复已应用！建议：<br>1. 增加爬取深度到3层<br>2. 优化URL过滤规则<br>3. 增加超时时间到15秒<br>4. 添加JavaScript渲染支持', 'success');
    }, 1000);
}

// 查看技术细节
function viewTechnicalDetails() {
    const detailsDiv = document.getElementById('technicalDetails');
    if (detailsDiv.style.display === 'none') {
        detailsDiv.style.display = 'block';
    } else {
        detailsDiv.style.display = 'none';
    }
}

// 显示结果
function showResult(elementId, message, type) {
    const element = document.getElementById(elementId);
    element.className = 'result show';
    element.innerHTML = message;
    
    // 根据类型设置样式
    if (type === 'success') {
        element.style.backgroundColor = '#d4edda';
        element.style.color = '#155724';
        element.style.borderColor = '#c3e6cb';
    } else if (type === 'error') {
        element.style.backgroundColor = '#f8d7da';
        element.style.color = '#721c24';
        element.style.borderColor = '#f5c6cb';
    } else if (type === 'warning') {
        element.style.backgroundColor = '#fff3cd';
        element.style.color = '#856404';
        element.style.borderColor = '#ffeaa7';
    } else {
        element.style.backgroundColor = '#d1ecf1';
        element.style.color = '#0c5460';
        element.style.borderColor = '#bee5eb';
    }
}

// 页面加载时初始化
window.onload = function() {
    // 默认显示第一个标签页
    showTab('our-result');
};