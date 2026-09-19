package io.github.ghgongjin.sitemap.service.push;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPConnectionClosedException;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.apache.commons.net.util.TrustManagerUtils;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;

/**
 * @ClassName FtpPushTransport
 * @Description FTP 传输实现（Commons Net；被动模式，先传临时文件再改名覆盖）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@Slf4j
@Component
public class FtpPushTransport implements PushTransport {

    private static final int REPLY_DETAIL_MAX = 200;

    private final SSLContext sslContext;

    public FtpPushTransport() {
        this(null);
    }

    FtpPushTransport(SSLContext sslContext) {
        this.sslContext = sslContext;
    }

    @Override
    public PushProtocol protocol() {
        return PushProtocol.FTP;
    }

    boolean useTls() {
        return false;
    }

    @Override
    public String verify(PushTarget target) throws PushTransportException {
        FTPClient client = null;
        try {
            client = open(target);
            ensureDirectory(client, target.remoteDirOrEmpty());
            return null;
        } catch (PushTransportException e) {
            throw e;
        } catch (Exception e) {
            throw classify(e);
        } finally {
            disconnectQuietly(client);
        }
    }

    @Override
    public String upload(PushTarget target, String remoteFileName, byte[] content) throws PushTransportException {
        FTPClient client = null;
        try {
            client = open(target);
            String dir = target.remoteDirOrEmpty();
            ensureDirectory(client, dir);
            publishFile(client, remoteFileName, content);
            log.info("{} 上传成功：{}:{} {}/{}（{} 字节）",
                    protocol(), target.host(), target.port(), dir, remoteFileName, content.length);
            return null;
        } catch (PushTransportException e) {
            throw e;
        } catch (Exception e) {
            throw classify(e);
        } finally {
            disconnectQuietly(client);
        }
    }

    private FTPClient open(PushTarget target) throws IOException, PushTransportException {
        FTPClient client = createClient();
        int timeout = target.timeoutMsOrDefault();
        client.setConnectTimeout(timeout);
        client.setDefaultTimeout(timeout);
        client.setDataTimeout(Duration.ofMillis(timeout));
        client.connect(target.host(), target.port());
        if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
            throw new PushTransportException(PushErrorCode.CONNECT_FAILED,
                    "远端服务器拒绝连接：" + replyDetail(client));
        }
        if (!client.login(target.username(), target.password())) {
            throw new PushTransportException(PushErrorCode.AUTH_FAILED,
                    "认证失败：" + replyDetail(client));
        }
        if (useTls()) {
            FTPSClient ftps = (FTPSClient) client;
            ftps.execPBSZ(0);
            ftps.execPROT("P");
        }
        client.setFileType(FTP.BINARY_FILE_TYPE);
        client.enterLocalPassiveMode();
        return client;
    }

    private FTPClient createClient() {
        if (!useTls()) {
            return new FTPClient();
        }
        FTPSClient client = sslContext != null ? new FTPSClient(false, sslContext) : new FTPSClient(false);
        if (sslContext == null) {
            client.setTrustManager(TrustManagerUtils.getValidateServerCertificateTrustManager());
        }
        client.setEndpointCheckingEnabled(true);
        return client;
    }

    private void ensureDirectory(FTPClient client, String dir) throws IOException, PushTransportException {
        if (dir.isEmpty()) {
            return;
        }
        if (!client.changeWorkingDirectory(dir)) {
            throw new PushTransportException(PushErrorCode.DIR_NOT_FOUND,
                    "远端目录不存在或不可进入：" + replyDetail(client));
        }
    }

    private void publishFile(FTPClient client, String remoteFileName, byte[] content)
            throws IOException, PushTransportException {
        String tmpName = remoteFileName + ".tmp-" + System.currentTimeMillis();
        store(client, tmpName, content);
        if (client.rename(tmpName, remoteFileName)) {
            return;
        }
        // 目标已存在且服务器不支持覆盖改名：删旧文件后重试，仍失败则回退直接覆盖写
        client.deleteFile(remoteFileName);
        if (client.rename(tmpName, remoteFileName)) {
            return;
        }
        store(client, remoteFileName, content);
        client.deleteFile(tmpName);
    }

    private void store(FTPClient client, String remoteName, byte[] content)
            throws IOException, PushTransportException {
        boolean stored;
        try (InputStream in = new ByteArrayInputStream(content)) {
            stored = client.storeFile(remoteName, in);
        }
        if (!stored) {
            int code = client.getReplyCode();
            if (code == 550) {
                throw new PushTransportException(PushErrorCode.PERMISSION_DENIED,
                        "远端目录无写权限：" + replyDetail(client));
            }
            throw new PushTransportException(PushErrorCode.UPLOAD_FAILED,
                    "上传文件失败：" + replyDetail(client));
        }
    }

    private String replyDetail(FTPClient client) {
        String reply = client.getReplyString();
        String detail = reply == null ? ("错误码 " + client.getReplyCode()) : reply.trim();
        return detail.length() <= REPLY_DETAIL_MAX ? detail : detail.substring(0, REPLY_DETAIL_MAX);
    }

    private PushTransportException classify(Exception e) {
        if (e instanceof SocketTimeoutException) {
            return new PushTransportException(PushErrorCode.TIMEOUT, "连接远端服务器超时", e);
        }
        if (e instanceof UnknownHostException || e instanceof ConnectException
                || e instanceof NoRouteToHostException) {
            return new PushTransportException(PushErrorCode.CONNECT_FAILED,
                    "无法连接远端服务器：" + e.getMessage(), e);
        }
        if (e instanceof SSLException) {
            return new PushTransportException(PushErrorCode.CONNECT_FAILED,
                    "TLS 握手失败（可能是证书不受信任、主机名不匹配或加密协商失败）：" + e.getMessage(), e);
        }
        if (e instanceof FTPConnectionClosedException) {
            return new PushTransportException(PushErrorCode.CONNECT_FAILED,
                    "连接被远端服务器关闭：" + e.getMessage(), e);
        }
        if (e instanceof IOException) {
            return new PushTransportException(PushErrorCode.CONNECT_FAILED,
                    "FTP 连接或传输失败：" + e.getMessage(), e);
        }
        return new PushTransportException(PushErrorCode.UPLOAD_FAILED, "上传失败：" + e.getMessage(), e);
    }

    private void disconnectQuietly(FTPClient client) {
        if (client != null && client.isConnected()) {
            try {
                client.logout();
            } catch (IOException ignored) {
                // 登出失败不影响结果
            }
            try {
                client.disconnect();
            } catch (IOException ignored) {
                // 断开失败不影响结果
            }
        }
    }
}
