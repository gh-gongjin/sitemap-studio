package io.github.ghgongjin.sitemap.service.push;

import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.sftp.SFTPException;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import net.schmizz.sshj.userauth.UserAuthException;
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile;
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/**
 * @ClassName SftpPushTransport
 * @Description SFTP 传输实现（SSHJ；主机密钥 TOFU，密码或私钥认证）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@Slf4j
@Component
public class SftpPushTransport implements PushTransport {

    private static final int WRITE_CHUNK_BYTES = 32 * 1024;

    @Override
    public PushProtocol protocol() {
        return PushProtocol.SFTP;
    }

    @Override
    public String verify(PushTarget target) throws PushTransportException {
        TofuHostKeyVerifier verifier = new TofuHostKeyVerifier(target.hostKeyFingerprint());
        SSHClient ssh = null;
        try {
            ssh = open(target, verifier);
            try (SFTPClient sftp = ssh.newSFTPClient()) {
                ensureDirectory(sftp, target.remoteDirOrEmpty());
            }
            return verifier.observed();
        } catch (PushTransportException e) {
            throw e;
        } catch (Exception e) {
            throw classify(e, verifier);
        } finally {
            closeQuietly(ssh);
        }
    }

    @Override
    public String upload(PushTarget target, String remoteFileName, byte[] content) throws PushTransportException {
        TofuHostKeyVerifier verifier = new TofuHostKeyVerifier(target.hostKeyFingerprint());
        SSHClient ssh = null;
        String dir = target.remoteDirOrEmpty();
        try {
            ssh = open(target, verifier);
            try (SFTPClient sftp = ssh.newSFTPClient()) {
                ensureDirectory(sftp, dir);
                replaceFile(sftp, dir, remoteFileName, content);
            }
            log.info("SFTP 上传成功：{}:{} {}/{}（{} 字节）",
                    target.host(), target.port(), dir, remoteFileName, content.length);
            return verifier.observed();
        } catch (PushTransportException e) {
            throw e;
        } catch (Exception e) {
            throw classify(e, verifier);
        } finally {
            closeQuietly(ssh);
        }
    }

    private SSHClient open(PushTarget target, TofuHostKeyVerifier verifier)
            throws IOException, PushTransportException {
        SSHClient ssh = new SSHClient();
        int timeout = target.timeoutMsOrDefault();
        ssh.setConnectTimeout(timeout);
        ssh.setTimeout(timeout);
        ssh.addHostKeyVerifier(verifier);
        ssh.connect(target.host(), target.port());
        if (target.usesPrivateKey()) {
            ssh.authPublickey(target.username(), loadPrivateKey(target.privateKeyPem()));
        } else {
            ssh.authPassword(target.username(), target.password());
        }
        return ssh;
    }

    private KeyProvider loadPrivateKey(String pem) throws PushTransportException {
        KeyProvider provider = tryLoadPrivateKey(pem, true);
        if (provider == null) {
            provider = tryLoadPrivateKey(pem, false);
        }
        if (provider == null) {
            throw new PushTransportException(PushErrorCode.AUTH_FAILED,
                    "私钥无法解析（支持 OpenSSH 与 PKCS#8 PEM 格式）");
        }
        return provider;
    }

    private KeyProvider tryLoadPrivateKey(String pem, boolean openSshFormat) {
        try {
            FileKeyProvider keyFile = openSshFormat ? new OpenSSHKeyFile() : new PKCS8KeyFile();
            keyFile.init(pem, null, null);
            keyFile.getPrivate();
            return keyFile;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void ensureDirectory(SFTPClient sftp, String dir) throws IOException, PushTransportException {
        if (dir.isEmpty()) {
            return;
        }
        FileAttributes attributes = sftp.stat(dir);
        FileMode.Type type = attributes.getType();
        if (type != null && type != FileMode.Type.DIRECTORY && type != FileMode.Type.UNKNOWN) {
            throw new PushTransportException(PushErrorCode.DIR_NOT_FOUND, "远端路径不是目录：" + dir);
        }
    }

    private void replaceFile(SFTPClient sftp, String dir, String remoteFileName, byte[] content) throws IOException {
        String finalPath = joinPath(dir, remoteFileName);
        String tmpPath = joinPath(dir, remoteFileName + ".tmp-" + System.currentTimeMillis());
        writeFile(sftp, tmpPath, content);
        try {
            sftp.rename(tmpPath, finalPath);
        } catch (IOException renameFailure) {
            // 目标已存在且服务器不支持覆盖改名：删旧文件后重试，仍失败则回退直接覆盖写
            removeQuietly(sftp, finalPath);
            try {
                sftp.rename(tmpPath, finalPath);
            } catch (IOException secondFailure) {
                writeFile(sftp, finalPath, content);
                removeQuietly(sftp, tmpPath);
            }
        }
    }

    private void writeFile(SFTPClient sftp, String path, byte[] content) throws IOException {
        try (RemoteFile file = sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))) {
            long offset = 0;
            for (int position = 0; position < content.length; position += WRITE_CHUNK_BYTES) {
                int length = Math.min(WRITE_CHUNK_BYTES, content.length - position);
                file.write(offset, content, position, length);
                offset += length;
            }
        }
    }

    private void removeQuietly(SFTPClient sftp, String path) {
        try {
            sftp.rm(path);
        } catch (IOException ignored) {
            // 清理失败不影响主流程
        }
    }

    private String joinPath(String dir, String name) {
        if (dir.isEmpty()) {
            return name;
        }
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    private PushTransportException classify(Exception e, TofuHostKeyVerifier verifier) {
        if (verifier != null && verifier.mismatchDetected()) {
            return new PushTransportException(PushErrorCode.HOST_KEY_MISMATCH,
                    "SFTP 主机密钥指纹与已记录的不一致，连接已被拒绝；如服务器已更换密钥，请重新保存指纹", e);
        }
        if (e instanceof UserAuthException) {
            return new PushTransportException(PushErrorCode.AUTH_FAILED, "认证失败：请检查用户名、密码或私钥", e);
        }
        if (e instanceof SocketTimeoutException) {
            return new PushTransportException(PushErrorCode.TIMEOUT, "连接远端服务器超时", e);
        }
        if (e instanceof UnknownHostException || e instanceof ConnectException
                || e instanceof NoRouteToHostException) {
            return new PushTransportException(PushErrorCode.CONNECT_FAILED,
                    "无法连接远端服务器：" + e.getMessage(), e);
        }
        if (e instanceof SFTPException sftpException) {
            return classifySftp(sftpException);
        }
        if (e instanceof IOException) {
            return new PushTransportException(PushErrorCode.CONNECT_FAILED,
                    "SFTP 连接或传输失败：" + e.getMessage(), e);
        }
        return new PushTransportException(PushErrorCode.UPLOAD_FAILED, "上传失败：" + e.getMessage(), e);
    }

    private PushTransportException classifySftp(SFTPException e) {
        PushErrorCode code = switch (e.getStatusCode()) {
            case NO_SUCH_FILE, NO_SUCH_PATH, NOT_A_DIRECTORY -> PushErrorCode.DIR_NOT_FOUND;
            case PERMISSION_DENIED, WRITE_PROTECT -> PushErrorCode.PERMISSION_DENIED;
            default -> PushErrorCode.UPLOAD_FAILED;
        };
        String message = switch (code) {
            case DIR_NOT_FOUND -> "远端目录不存在或不可进入：" + e.getMessage();
            case PERMISSION_DENIED -> "远端目录无写权限：" + e.getMessage();
            default -> "远端文件操作失败：" + e.getMessage();
        };
        return new PushTransportException(code, message, e);
    }

    private void closeQuietly(SSHClient ssh) {
        if (ssh != null) {
            try {
                ssh.close();
            } catch (IOException ignored) {
                // 关闭失败不影响结果
            }
        }
    }

    /**
     * 首次连接接受并记录主机密钥指纹（TOFU）；已记录指纹时严格比对，不一致即拒绝
     */
    static final class TofuHostKeyVerifier implements HostKeyVerifier {

        private final String expectedFingerprint;
        private volatile String observed;
        private volatile boolean mismatch;

        TofuHostKeyVerifier(String expectedFingerprint) {
            this.expectedFingerprint = expectedFingerprint == null || expectedFingerprint.isBlank()
                    ? null : expectedFingerprint.trim();
        }

        @Override
        public boolean verify(String hostname, int port, PublicKey key) {
            String fingerprint = openSshFingerprint(key);
            observed = fingerprint;
            if (expectedFingerprint == null || expectedFingerprint.equalsIgnoreCase(fingerprint)) {
                return true;
            }
            mismatch = true;
            return false;
        }

        @Override
        public List<String> findExistingAlgorithms(String hostname, int port) {
            return Collections.emptyList();
        }

        String observed() {
            return observed;
        }

        boolean mismatchDetected() {
            return mismatch;
        }

        static String openSshFingerprint(PublicKey key) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
                return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 不可用", e);
            }
        }
    }
}
