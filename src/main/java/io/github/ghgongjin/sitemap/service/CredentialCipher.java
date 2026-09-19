package io.github.ghgongjin.sitemap.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * @ClassName CredentialCipher
 * @Description 推送凭据加密（AES-256-GCM）：密钥优先取配置，其次读取/自动生成密钥文件
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@Slf4j
@Component
public class CredentialCipher {

    static final String PREFIX = "v1:";
    static final int KEY_LENGTH = 32;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final String configuredKey;
    private final Path keyFile;
    private final SecureRandom random = new SecureRandom();
    private volatile SecretKeySpec key;

    @Autowired
    public CredentialCipher(@Value("${sitemap.push.crypto-key:}") String configuredKey,
                            @Value("${sitemap.push.key-file:./data/push.key}") String keyFile) {
        this(configuredKey, Path.of(keyFile));
    }

    CredentialCipher(String configuredKey, Path keyFile) {
        this.configuredKey = configuredKey == null ? "" : configuredKey.trim();
        this.keyFile = keyFile;
    }

    /**
     * 加密明文凭据；空白输入直接返回 null（视为未配置）
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("凭据加密失败：" + e.getMessage(), e);
        }
    }

    /**
     * 解密凭据；格式不符、被篡改或密钥不匹配时抛出 IllegalStateException
     */
    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        if (!encrypted.startsWith(PREFIX)) {
            throw new IllegalStateException("凭据格式无法识别");
        }
        try {
            byte[] packed = Base64.getDecoder().decode(encrypted.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, packed, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(packed, IV_LENGTH, packed.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("凭据解密失败：密钥不匹配或数据已损坏", e);
        }
    }

    private SecretKeySpec key() {
        SecretKeySpec current = key;
        if (current == null) {
            synchronized (this) {
                if (key == null) {
                    key = loadOrCreateKey();
                }
                current = key;
            }
        }
        return current;
    }

    private SecretKeySpec loadOrCreateKey() {
        if (!configuredKey.isEmpty()) {
            return new SecretKeySpec(decodeKey(configuredKey), "AES");
        }
        try {
            if (Files.notExists(keyFile)) {
                Path parent = keyFile.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                byte[] fresh = new byte[KEY_LENGTH];
                random.nextBytes(fresh);
                Files.writeString(keyFile, Base64.getEncoder().encodeToString(fresh));
                log.info("已生成推送凭据加密密钥文件：{}", keyFile.toAbsolutePath());
            }
            return new SecretKeySpec(decodeKey(Files.readString(keyFile).trim()), "AES");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("推送密钥文件不可用：" + keyFile + "（" + e.getMessage() + "）", e);
        }
    }

    private byte[] decodeKey(String value) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("推送密钥必须是 Base64 编码的 32 字节内容");
        }
        if (decoded.length != KEY_LENGTH) {
            throw new IllegalStateException("推送密钥必须是 Base64 编码的 32 字节内容");
        }
        return decoded;
    }
}
