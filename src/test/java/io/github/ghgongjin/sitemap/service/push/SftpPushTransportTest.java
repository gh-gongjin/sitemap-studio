package io.github.ghgongjin.sitemap.service.push;

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @ClassName SftpPushTransportTest
 * @Description SFTP 传输层集成测试（嵌入式 Apache MINA SSHD，本地受控）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
class SftpPushTransportTest {

    static final String USER = "pushuser";
    static final String PASSWORD = "s3cret-pass";
    static final byte[] XML = "<?xml version=\"1.0\"?><urlset></urlset>".getBytes(StandardCharsets.UTF_8);

    @TempDir
    static Path tempDir;

    static SshServer sshd;
    static Path remoteRoot;
    static KeyPair clientKey;
    static int port;

    final SftpPushTransport transport = new SftpPushTransport();

    @BeforeAll
    static void startServer() throws Exception {
        remoteRoot = tempDir.resolve("remote");
        Files.createDirectories(remoteRoot);
        clientKey = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        SimpleGeneratorHostKeyProvider hostKeyProvider =
                new SimpleGeneratorHostKeyProvider(tempDir.resolve("hostkey.ser"));
        hostKeyProvider.setAlgorithm("RSA");

        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(hostKeyProvider);
        sshd.setPasswordAuthenticator((user, pass, session) -> USER.equals(user) && PASSWORD.equals(pass));
        sshd.setPublickeyAuthenticator((user, key, session) -> USER.equals(user) && key.equals(clientKey.getPublic()));
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(remoteRoot));
        sshd.start();
        port = sshd.getPort();
    }

    @AfterAll
    static void stopServer() throws IOException {
        if (sshd != null) {
            sshd.stop(true);
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
    void shouldUploadFileWhenPasswordAuth() throws Exception {
        // Given
        PushTarget target = passwordTarget("");

        // When
        transport.upload(target, "sitemap.xml", XML);

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
        transport.upload(passwordTarget(""), "overwrite.xml", XML);

        // Then
        assertThat(Files.readAllBytes(existing)).isEqualTo(XML);
        assertThat(fileNames(remoteRoot)).containsExactly("overwrite.xml");
    }

    @Test
    void shouldUploadIntoSubdirectoryWhenRemoteDirGiven() throws Exception {
        // Given
        Files.createDirectories(remoteRoot.resolve("public"));
        PushTarget target = passwordTarget("public");

        // When
        transport.upload(target, "sitemap.xml", XML);

        // Then
        assertThat(Files.readAllBytes(remoteRoot.resolve("public/sitemap.xml"))).isEqualTo(XML);
    }

    @Test
    void shouldReturnFingerprintWhenTofuFirstConnect() throws Exception {
        // Given
        PushTarget target = passwordTarget("");

        // When
        String first = transport.verify(target);
        String second = transport.verify(passwordTarget(""));

        // Then
        assertThat(first).startsWith("SHA256:").hasSizeGreaterThan(20);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void shouldAcceptWhenFingerprintMatches() throws Exception {
        // Given
        String fingerprint = transport.verify(passwordTarget(""));

        // When & Then
        assertThat(transport.verify(passwordTarget("", fingerprint))).isEqualTo(fingerprint);
    }

    @Test
    void shouldFailWithHostKeyMismatchWhenFingerprintWrong() {
        // Given
        PushTarget target = passwordTarget("", "SHA256:AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIIIIJJJJ");

        // When & Then
        assertThatThrownBy(() -> transport.verify(target))
                .isInstanceOf(PushTransportException.class)
                .satisfies(e -> assertThat(((PushTransportException) e).errorCode())
                        .isEqualTo(PushErrorCode.HOST_KEY_MISMATCH));
    }

    @Test
    void shouldFailWithAuthFailedWhenPasswordWrong() {
        // Given
        PushTarget target = new PushTarget(PushProtocol.SFTP, "127.0.0.1", port,
                USER, "wrong-password", null, "", null, 10_000);

        // When & Then
        assertThatThrownBy(() -> transport.upload(target, "sitemap.xml", XML))
                .isInstanceOf(PushTransportException.class)
                .satisfies(e -> assertThat(((PushTransportException) e).errorCode())
                        .isEqualTo(PushErrorCode.AUTH_FAILED));
    }

    @Test
    void shouldFailWithDirNotFoundWhenRemoteDirMissing() {
        // Given
        PushTarget target = passwordTarget("missing-dir");

        // When & Then
        assertThatThrownBy(() -> transport.upload(target, "sitemap.xml", XML))
                .isInstanceOf(PushTransportException.class)
                .satisfies(e -> assertThat(((PushTransportException) e).errorCode())
                        .isEqualTo(PushErrorCode.DIR_NOT_FOUND));
    }

    @Test
    void shouldUploadWhenPublicKeyAuth() throws Exception {
        // Given
        String pem = pkcs8Pem(clientKey);
        PushTarget target = new PushTarget(PushProtocol.SFTP, "127.0.0.1", port,
                USER, null, pem, "", null, 10_000);

        // When
        transport.upload(target, "key-auth.xml", XML);

        // Then
        assertThat(Files.readAllBytes(remoteRoot.resolve("key-auth.xml"))).isEqualTo(XML);
    }

    @Test
    void shouldFailWithConnectFailedWhenPortClosed() throws Exception {
        // Given
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        PushTarget target = new PushTarget(PushProtocol.SFTP, "127.0.0.1", closedPort,
                USER, PASSWORD, null, "", null, 5_000);

        // When & Then
        assertThatThrownBy(() -> transport.verify(target))
                .isInstanceOf(PushTransportException.class)
                .satisfies(e -> assertThat(((PushTransportException) e).errorCode())
                        .isEqualTo(PushErrorCode.CONNECT_FAILED));
    }

    private static PushTarget passwordTarget(String remoteDir) {
        return passwordTarget(remoteDir, null);
    }

    private static PushTarget passwordTarget(String remoteDir, String hostKeyFingerprint) {
        return new PushTarget(PushProtocol.SFTP, "127.0.0.1", port,
                USER, PASSWORD, null, remoteDir, hostKeyFingerprint, 10_000);
    }

    private static List<String> fileNames(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static String pkcs8Pem(KeyPair keyPair) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----";
    }
}
