package io.github.ghgongjin.sitemap.service.push;

import io.github.ghgongjin.sitemap.entity.PushConfig;
import io.github.ghgongjin.sitemap.entity.PushLog;
import io.github.ghgongjin.sitemap.repository.PushConfigRepository;
import io.github.ghgongjin.sitemap.repository.PushLogRepository;
import io.github.ghgongjin.sitemap.service.CredentialCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * @ClassName PushConfigService
 * @Description 推送配置存储层：表单校验、凭据加密与保留、视图脱敏、日志查询
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@Slf4j
@Service
public class PushConfigService {

    static final String AUTH_PASSWORD = "PASSWORD";
    static final String AUTH_PRIVATE_KEY = "PRIVATE_KEY";
    static final int MIN_PORT = 1;
    static final int MAX_PORT = 65535;
    static final String DEFAULT_SITEMAP_FILE_NAME = "sitemap.xml";

    private static final Pattern HOST_PATTERN = Pattern.compile("[^\\s/:]+");
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,255}");
    private static final Pattern INDEX_NOW_KEY_PATTERN = Pattern.compile("[0-9a-fA-F]{8,128}");
    /** 私钥长度按 PEM 文本限制，留出加密膨胀余量 */
    private static final int PRIVATE_KEY_MAX_LENGTH = 4096;
    private static final int PASSWORD_MAX_LENGTH = 512;

    private final PushConfigRepository configRepository;
    private final PushLogRepository logRepository;
    private final CredentialCipher credentialCipher;

    public PushConfigService(PushConfigRepository configRepository,
                             PushLogRepository logRepository,
                             CredentialCipher credentialCipher) {
        this.configRepository = configRepository;
        this.logRepository = logRepository;
        this.credentialCipher = credentialCipher;
    }

    /**
     * 保存（新建或更新）推送设置；凭据留空表示沿用已存值
     */
    @Transactional
    public PushConfig save(Long siteId, PushSettings settings) {
        PushProtocol protocol = parseProtocol(settings.protocol());
        String host = requireHost(settings.host());
        int port = requirePort(settings.port());
        String username = requireText(settings.username(), "用户名", 256);
        String authType = parseAuthType(settings.authType());
        if (protocol != PushProtocol.SFTP && AUTH_PRIVATE_KEY.equals(authType)) {
            throw new IllegalArgumentException("仅 SFTP 支持私钥认证");
        }
        String remoteDir = trimToNull(settings.remoteDir(), 1024, "远端目录");
        String fileName = requireFileName(settings.sitemapFileName());
        String indexNowKey = requireIndexNowKey(settings.indexNowEnabled(), settings.indexNowKey());

        LocalDateTime now = LocalDateTime.now();
        PushConfig config = configRepository.findBySiteId(siteId)
                .orElseGet(() -> newConfig(siteId, now));
        config.setEnabled(settings.enabled());
        config.setProtocol(protocol.name());
        config.setHost(host);
        config.setPort(port);
        config.setUsername(username);
        config.setAuthType(authType);
        config.setRemoteDir(remoteDir);
        config.setSitemapFileName(fileName);
        config.setIndexNowEnabled(settings.indexNowEnabled());
        config.setIndexNowKey(indexNowKey);
        applyCredential(config, authType, settings.password(), settings.privateKey());
        config.setUpdatedAt(now);

        PushConfig saved = configRepository.save(config);
        log.info("推送设置已保存：siteId={}，{} → {}:{}", siteId, protocol, host, port);
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<PushConfigView> view(Long siteId) {
        return siteId == null ? Optional.empty() : configRepository.findBySiteId(siteId).map(PushConfigView::of);
    }

    @Transactional(readOnly = true)
    public List<PushLog> logs(Long siteId) {
        return siteId == null ? List.of() : logRepository.findBySiteIdOrderByIdDesc(siteId);
    }

    @Transactional
    public void delete(Long siteId) {
        configRepository.deleteBySiteId(siteId);
        logRepository.deleteBySiteId(siteId);
    }

    private void applyCredential(PushConfig config, String authType, String password, String privateKey) {
        if (AUTH_PASSWORD.equals(authType)) {
            String value = trimToNull(password, PASSWORD_MAX_LENGTH, "密码");
            if (value != null) {
                config.setPasswordEnc(credentialCipher.encrypt(value));
            } else if (!hasText(config.getPasswordEnc())) {
                throw new IllegalArgumentException("请输入密码");
            }
        } else {
            String value = trimToNull(privateKey, PRIVATE_KEY_MAX_LENGTH, "私钥内容");
            if (value != null) {
                config.setPrivateKeyEnc(credentialCipher.encrypt(value));
            } else if (!hasText(config.getPrivateKeyEnc())) {
                throw new IllegalArgumentException("请粘贴私钥内容");
            }
        }
    }

    private PushConfig newConfig(Long siteId, LocalDateTime now) {
        PushConfig config = new PushConfig();
        config.setSiteId(siteId);
        config.setCreatedAt(now);
        return config;
    }

    private PushProtocol parseProtocol(String value) {
        try {
            return PushProtocol.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的推送协议：" + value);
        }
    }

    private String parseAuthType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!AUTH_PASSWORD.equals(normalized) && !AUTH_PRIVATE_KEY.equals(normalized)) {
            throw new IllegalArgumentException("不支持的认证方式：" + value);
        }
        return normalized;
    }

    private String requireHost(String value) {
        String host = value == null ? "" : value.trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("主机不能为空");
        }
        if (host.length() > 512 || !HOST_PATTERN.matcher(host).matches()) {
            throw new IllegalArgumentException("主机格式不正确");
        }
        return host;
    }

    private int requirePort(int port) {
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException("端口必须在 " + MIN_PORT + " 到 " + MAX_PORT + " 之间");
        }
        return port;
    }

    private String requireFileName(String value) {
        String name = value == null || value.isBlank() ? DEFAULT_SITEMAP_FILE_NAME : value.trim();
        if (!FILE_NAME_PATTERN.matcher(name).matches() || name.contains("..")) {
            throw new IllegalArgumentException("站点地图文件名不合法（仅限字母、数字、点、下划线与短横线）");
        }
        return name;
    }

    private String requireIndexNowKey(boolean enabled, String value) {
        String key = trimToNull(value, 64, "IndexNow key");
        if (enabled && (key == null || !INDEX_NOW_KEY_PATTERN.matcher(key).matches())) {
            throw new IllegalArgumentException("IndexNow key 必须是 8-128 位十六进制字符");
        }
        return key;
    }

    private String requireText(String value, String label, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(label + "过长（最多 " + maxLength + " 字符）");
        }
        return trimmed;
    }

    private String trimToNull(String value, int maxLength, String label) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(label + "过长（最多 " + maxLength + " 字符）");
        }
        return trimmed;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
