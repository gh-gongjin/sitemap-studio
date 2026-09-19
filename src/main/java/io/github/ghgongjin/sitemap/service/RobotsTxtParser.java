package io.github.ghgongjin.sitemap.service;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @ClassName RobotsTxtParser
 * @Description robots.txt 解析器，遵循爬虫规则
 * @Author gj
 * @Date 2026/9/15
 * @Version 1.0
 */
@Slf4j
public class RobotsTxtParser {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int TIMEOUT_MS = 10000;

    private final Map<String, List<String>> disallowRules = new ConcurrentHashMap<>();
    private final Map<String, List<String>> allowRules = new ConcurrentHashMap<>();
    private final List<String> sitemapUrls = Collections.synchronizedList(new ArrayList<>());
    
    private String baseUrl;
    private boolean parsed = false;

    /**
     * 解析 robots.txt
     */
    public void parse(String baseUrl) {
        parse(baseUrl, new CrawlUrlPolicy(), new SafeHttpFetcher(), TIMEOUT_MS);
    }

    public void parse(String baseUrl, CrawlUrlPolicy policy, SafeHttpFetcher fetcher, int timeoutMs) {
        this.baseUrl = baseUrl;
        
        try {
            String robotsUrl = baseUrl + "/robots.txt";
            log.info("解析 robots.txt: {}", robotsUrl);
            SafeHttpFetcher.Response response = fetcher.fetch(robotsUrl, baseUrl, timeoutMs, null);
            
            if (response.statusCode() != 200) {
                log.info("robots.txt 不存在或无法访问 (HTTP {})", response.statusCode());
                parsed = true;
                return;
            }
            
            String contentType = response.contentType();
            if (contentType != null && !contentType.contains("text/plain")) {
                log.warn("robots.txt 内容类型异常: {}", contentType);
            }
            
            String content = response.text();
            parseContent(content);
            
            parsed = true;
            log.info("robots.txt 解析完成，发现 {} 个 Sitemap", sitemapUrls.size());
            
        } catch (SecurityException e) {
            log.warn("拒绝不安全的 robots.txt: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("解析 robots.txt 失败: {}", e.getMessage());
            parsed = true;
        }
    }

    /**
     * 解析 robots.txt 内容
     */
    private void parseContent(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        
        String currentUserAgent = "*";
        List<String> currentDisallow = new ArrayList<>();
        List<String> currentAllow = new ArrayList<>();
        
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                // 跳过空行和注释
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                
                // 解析指令
                int colonIndex = line.indexOf(':');
                if (colonIndex == -1) {
                    continue;
                }
                
                String directive = line.substring(0, colonIndex).trim().toLowerCase();
                String value = line.substring(colonIndex + 1).trim();
                
                // 移除注释
                int commentIndex = value.indexOf('#');
                if (commentIndex != -1) {
                    value = value.substring(0, commentIndex).trim();
                }
                
                switch (directive) {
                    case "user-agent":
                        // 如果之前有规则，保存它们
                        if (!currentDisallow.isEmpty() || !currentAllow.isEmpty()) {
                            saveRules(currentUserAgent, currentDisallow, currentAllow);
                            currentDisallow = new ArrayList<>();
                            currentAllow = new ArrayList<>();
                        }
                        currentUserAgent = value.toLowerCase();
                        break;
                        
                    case "disallow":
                        if (!value.isEmpty()) {
                            currentDisallow.add(value);
                        }
                        break;
                        
                    case "allow":
                        if (!value.isEmpty()) {
                            currentAllow.add(value);
                        }
                        break;
                        
                    case "sitemap":
                        if (!value.isEmpty()) {
                            sitemapUrls.add(value);
                        }
                        break;
                }
            }
            
            // 保存最后一组规则
            if (!currentDisallow.isEmpty() || !currentAllow.isEmpty()) {
                saveRules(currentUserAgent, currentDisallow, currentAllow);
            }
            
        } catch (Exception e) {
            log.error("解析 robots.txt 内容失败: {}", e.getMessage());
        }
    }

    /**
     * 保存规则
     */
    private void saveRules(String userAgent, List<String> disallow, List<String> allow) {
        // 只关心通配符规则或匹配我们 User-Agent 的规则
        if (userAgent.equals("*") || userAgent.contains("mozilla") || userAgent.contains("chrome")) {
            disallowRules.put(userAgent, new ArrayList<>(disallow));
            allowRules.put(userAgent, new ArrayList<>(allow));
            log.debug("保存 robots 规则 [{}]: {} 个 Disallow, {} 个 Allow",
                    userAgent, disallow.size(), allow.size());
        }
    }

    /**
     * 检查 URL 是否允许爬取
     */
    public boolean isAllowed(String url) {
        if (!parsed || baseUrl == null) {
            return true;
        }
        
        try {
            URL parsedUrl = new URL(url);
            String path = parsedUrl.getPath();
            if (parsedUrl.getQuery() != null) {
                path += "?" + parsedUrl.getQuery();
            }
            
            return isAllowedByPath(path);
            
        } catch (Exception e) {
            log.debug("检查 URL 允许性失败: {} - {}", url, e.getMessage());
            return true;
        }
    }

    /**
     * 根据路径检查是否允许爬取
     * 按 robots.txt 规范：找到最具体的匹配规则，Allow 优先于 Disallow
     */
    private boolean isAllowedByPath(String path) {
        boolean foundDisallow = false;
        boolean foundAllow = false;
        int bestMatchLength = -1;
        
        for (Map.Entry<String, List<String>> entry : disallowRules.entrySet()) {
            String userAgent = entry.getKey();
            List<String> disallows = entry.getValue();
            List<String> allows = allowRules.getOrDefault(userAgent, Collections.emptyList());
            
            for (String rule : disallows) {
                if (matchesRule(path, rule)) {
                    int ruleLen = rule.replace("*", "").replace("$", "").length();
                    if (ruleLen > bestMatchLength) {
                        bestMatchLength = ruleLen;
                        foundDisallow = true;
                        foundAllow = false;
                    } else if (ruleLen == bestMatchLength) {
                        foundDisallow = true;
                    }
                }
            }
            
            for (String rule : allows) {
                if (matchesRule(path, rule)) {
                    int ruleLen = rule.replace("*", "").replace("$", "").length();
                    if (ruleLen > bestMatchLength) {
                        bestMatchLength = ruleLen;
                        foundAllow = true;
                        foundDisallow = false;
                    } else if (ruleLen == bestMatchLength) {
                        foundAllow = true;
                    }
                }
            }
        }
        
        if (foundAllow) {
            return true;
        }
        if (foundDisallow) {
            return false;
        }
        return true;
    }

    /**
     * 检查路径是否匹配规则
     */
    private boolean matchesRule(String path, String rule) {
        if (rule.isEmpty()) {
            return false;
        }
        
        // 处理通配符
        if (rule.contains("*")) {
            String regex = rule.replace("*", ".*");
            return path.matches(regex);
        }
        
        // 处理 $ 结尾（精确匹配）
        if (rule.endsWith("$")) {
            String exactPath = rule.substring(0, rule.length() - 1);
            return path.equals(exactPath);
        }
        
        // 普通前缀匹配
        return path.startsWith(rule);
    }

    /**
     * 获取发现的 Sitemap URL 列表
     */
    public List<String> getSitemapUrls() {
        return new ArrayList<>(sitemapUrls);
    }

    /**
     * 是否已解析
     */
    public boolean isParsed() {
        return parsed;
    }
}
