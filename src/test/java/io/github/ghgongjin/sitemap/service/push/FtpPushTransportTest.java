package io.github.ghgongjin.sitemap.service.push;

import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.ssl.SslConfigurationFactory;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @ClassName FtpPushTransportTest
 * @Description FTP/FTPS 传输层集成测试（嵌入式 Apache FtpServer，本地受控）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
class FtpPushTransportTest {

    static final String USER = "pushuser";
    static final String PASSWORD = "s3cret-pass";
    static final byte[] XML = "<?xml version=\"1.0\"?><urlset></urlset>".getBytes(StandardCharsets.UTF_8);
    static final String KEYSTORE_PASSWORD = "changeit";

    @TempDir
    static Path tempDir;

    static FtpServer ftpServer;
    static Path remoteRoot;
    static int port;

    final FtpPushTransport ftp = new FtpPushTransport();
    final FtpPushTransport ftps = new FtpsPushTransport(trustContext(certPath("ftps-cert-a.p12")));

    @BeforeAll
    static void startServer() throws Exception {
        remoteRoot = tempDir.resolve("remote");
        Files.createDirectories(remoteRoot);
        port = freePort();

        FtpServerFactory factory = new FtpServerFactory();

        ListenerFactory listenerFactory = new ListenerFactory();
        listenerFactory.setPort(port);
        listenerFactory.setServerAddress("127.0.0.1");

        SslConfigurationFactory ssl = new SslConfigurationFactory();
        ssl.setKeystoreFile(certPath("ftps-cert-a.p12").toFile());
        ssl.setKeystorePassword(KEYSTORE_PASSWORD);
        ssl.setKeystoreType("PKCS12");
        listenerFactory.setSslConfiguration(ssl.createSslConfiguration());
        listenerFactory.setImplicitSsl(false);
        factory.addListener("default", listenerFactory.createListener());

        PropertiesUserManagerFactory userManagerFactory = new PropertiesUserManagerFactory();
        Path usersFile = tempDir.resolve("users.properties");
        Files.writeString(usersFile, "");
        userManagerFactory.setFile(usersFile.toFile());
        UserManager userManager = userManagerFactory.createUserManager();
        BaseUser user = new BaseUser();
        user.setName(USER);
        user.setPassword(PASSWORD);
        user.setHomeDirectory(remoteRoot.toString());
        user.setEnabled(true);
        user.setAuthorities(List.of((Authority) new WritePermission()));
        userManager.save(user);
        factory.setUserManager(userManager);

        ftpServer = factory.createServer();
        ftpServer.start();
    }

    @AfterAll
    static void stopServer() {
        if (ftpServer != null) {
            ftpServer.stop();
        }
    }

    @BeforeEach
    void cleanRemoteRoot() throws IOException {
        if (Files.exists(remoteRoot)) {
            try (Stream<Path> walk = Files.walk(remoteRoot)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException ignored) {
                        // 清理尽力而为
                    }
                });
            }
        }
        Files.createDirectories(remoteRoot);
    }

    @Test
    void shouldUploadFileWhenFtpAuthOk() throws Exception {
        // Given
        PushTarget target = ftpTarget("");

        // When
        ftp.upload(target, "sitemap.xml", XML);

        // Then
        Path uploaded = remoteRoot.resolve("sitemap.xml");
        assertThat(uploaded).exists();
        assertThat(Files.readAllBytes(uploaded)).isEqualTo(XML);
        assertThat(fileNames(remoteRoot)).containsExactly("sitemap.xml");
    }

    @Test
    void shouldOverwriteExistingFileWhenTargetExists() throws Exception {
        // Given
        Path existing = remoteRoot.resolve("overwrite.xml");
        Files.writeString(existing, "old-content");

        // When
        ftp.upload(ftpTarget(""), "overwrite.xml", XML);

        // Then
        assertThat(Files.readAllBytes(existing)).isEqualTo(XML);
        assertThat(fileNames(remoteRoot)).containsExactly("overwrite.xml");
    }

    @Test
    void shouldUploadIntoSubdirectoryWhenRemoteDirGiven() throws Exception {
        // Given
        Files.createDirectories(remoteRoot.resolve("public"));

        // When
        ftp.upload(ftpTarget("public"), "sitemap.xml", XML);

        // Then
        assertThat(Files.readAllBytes(remoteRoot.resolve("public/sitemap.xml"))).isEqualTo(XML);
    }

    @Test
    void shouldUploadFileWhenExplicitFtps() throws Exception {
        // When
        ftps.upload(ftpsTarget(""), "sitemap.xml", XML);

        // Then
        assertThat(Files.readAllBytes(remoteRoot.resolve("sitemap.xml"))).isEqualTo(XML);
    }

    @Test
    void shouldFailWithConnectFailedWhenFtpsCertUntrusted() {
        // Given
        FtpPushTransport untrusting = new FtpsPushTransport(trustContext(certPath("ftps-cert-b.p12")));

        // When & Then
        assertThatThrownBy(() -> untrusting.upload(ftpsTarget(""), "sitemap.xml", XML))
                .isInstanceOf(PushTransportException.class)
                .satisfies(e -> assertThat(((PushTransportException) e).errorCode())
                        .isEqualTo(PushErrorCode.CONNECT_FAILED));
    }

    @Test
    void shouldFailWithAuthFailedWhenPasswordWrong() {
        // Given
        PushTarget target = new PushTarget(PushProtocol.FTP, "127.0.0.1", port,
                USER, "wrong-password", null, "", null, 10_000);

        // When & Then
        assertThatThrownBy(() -> ftp.upload(target, "sitemap.xml", XML))
                .isInstanceOf(PushTransportException.class)
                .satisfies(e -> assertThat(((PushTransportException) e).errorCode())
                        .isEqualTo(PushErrorCode.AUTH_FAILED));
    }

    @Test
    void shouldFailWithDirNotFoundWhenRemoteDirMissing() {
        // When & Then
        assertThatThrownBy(() -> ftp.upload(ftpTarget("missing-dir"), "sitemap.xml", XML))
                .isInstanceOf(PushTransportException.class)
                .satisfies(e -> assertThat(((PushTransportException) e).errorCode())
                        .isEqualTo(PushErrorCode.DIR_NOT_FOUND));
    }

    @Test
    void shouldFailWithConnectFailedWhenPortClosed() throws Exception {
        // Given
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        PushTarget target = new PushTarget(PushProtocol.FTP, "127.0.0.1", closedPort,
                USER, PASSWORD, null, "", null, 5_000);

        // When & Then
        assertThatThrownBy(() -> ftp.verify(target))
                .isInstanceOf(PushTransportException.class)
                .satisfies(e -> assertThat(((PushTransportException) e).errorCode())
                        .isEqualTo(PushErrorCode.CONNECT_FAILED));
    }

    private static PushTarget ftpTarget(String remoteDir) {
        return new PushTarget(PushProtocol.FTP, "127.0.0.1", port,
                USER, PASSWORD, null, remoteDir, null, 10_000);
    }

    private static PushTarget ftpsTarget(String remoteDir) {
        return new PushTarget(PushProtocol.FTPS, "127.0.0.1", port,
                USER, PASSWORD, null, remoteDir, null, 10_000);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path certPath(String name) {
        return Path.of("src", "test", "resources", "push", name);
    }

    private static SSLContext trustContext(Path keystoreFile) {
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keystoreFile)) {
                keyStore.load(in, KEYSTORE_PASSWORD.toCharArray());
            }
            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keyStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagerFactory.getTrustManagers(), null);
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("测试信任库初始化失败", e);
        }
    }

    private static List<String> fileNames(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
