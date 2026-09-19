package io.github.ghgongjin.sitemap.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialCipherTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRoundTripWhenEncryptThenDecrypt() {
        // Given
        CredentialCipher cipher = new CredentialCipher("", keyFile("push.key"));

        // When
        String encrypted = cipher.encrypt("p@ss w0rd-示例#1");
        String decrypted = cipher.decrypt(encrypted);

        // Then
        assertThat(encrypted).startsWith("v1:").doesNotContain("p@ss");
        assertThat(decrypted).isEqualTo("p@ss w0rd-示例#1");
    }

    @Test
    void shouldUseFreshIvWhenSameInputEncryptedTwice() {
        // Given
        CredentialCipher cipher = new CredentialCipher("", keyFile("push.key"));

        // When
        String first = cipher.encrypt("same");
        String second = cipher.encrypt("same");

        // Then
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void shouldFailWhenCiphertextTampered() {
        // Given
        CredentialCipher cipher = new CredentialCipher("", keyFile("push.key"));
        String encrypted = cipher.encrypt("secret");
        byte[] raw = Base64.getDecoder().decode(encrypted.substring(3));
        raw[raw.length - 1] ^= 0x01;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);

        // When & Then
        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldFailWhenKeyChangedBetweenEncryptAndDecrypt() {
        // Given
        String encrypted = new CredentialCipher("", keyFile("push.key")).encrypt("secret");
        CredentialCipher other = new CredentialCipher("", keyFile("other.key"));

        // When & Then
        assertThatThrownBy(() -> other.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldCreateKeyFileWhenMissing() {
        // Given
        Path keyFile = keyFile("push.key");
        assertThat(keyFile).doesNotExist();

        // When
        new CredentialCipher("", keyFile).encrypt("x");

        // Then
        assertThat(keyFile).exists();
    }

    @Test
    void shouldPreferConfiguredKeyOverKeyFile() {
        // Given
        String configured = Base64.getEncoder().encodeToString(new byte[32]);
        Path keyFile = keyFile("unused.key");

        // When
        CredentialCipher first = new CredentialCipher(configured, keyFile);
        CredentialCipher second = new CredentialCipher(configured, keyFile("elsewhere.key"));

        // Then
        assertThat(second.decrypt(first.encrypt("hello"))).isEqualTo("hello");
        assertThat(keyFile).doesNotExist();
    }

    @Test
    void shouldRejectKeyThatIsNot32Bytes() {
        // Given
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);
        CredentialCipher cipher = new CredentialCipher(tooShort, keyFile("push.key"));

        // When & Then
        assertThatThrownBy(() -> cipher.encrypt("x"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldPassThroughBlankValues() {
        // Given
        CredentialCipher cipher = new CredentialCipher("", keyFile("push.key"));

        // Then
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.encrypt("   ")).isNull();
        assertThat(cipher.decrypt(null)).isNull();
        assertThat(cipher.decrypt("")).isNull();
    }

    private Path keyFile(String name) {
        return tempDir.resolve(name);
    }
}
