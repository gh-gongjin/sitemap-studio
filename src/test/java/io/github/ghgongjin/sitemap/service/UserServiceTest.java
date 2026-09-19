package io.github.ghgongjin.sitemap.service;

import io.github.ghgongjin.sitemap.entity.UserAccount;
import io.github.ghgongjin.sitemap.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import({UserService.class, SecurityUtilsTestConfig.class})
class UserServiceTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserAccountRepository repository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void shouldStoreBcryptHashWhenRegister() {
        UserAccount saved = userService.register("alice", "Passw0rd1");

        assertThat(saved.getId()).isNotNull();
        // DelegatingPasswordEncoder 输出带 {bcrypt} id 前缀（与 Task 2 断言一致）
        assertThat(saved.getPasswordHash()).isNotEqualTo("Passw0rd1")
                .startsWith("{bcrypt}$2a$");
        assertThat(passwordEncoder.matches("Passw0rd1", saved.getPasswordHash())).isTrue();
    }

    @Test
    void shouldRejectDuplicateUsernameIgnoringCase() {
        userService.register("bob", "Passw0rd1");

        assertThatThrownBy(() -> userService.register("BOB", "Passw0rd2"))
                .isInstanceOf(RegistrationException.class)
                .extracting(e -> ((RegistrationException) e).reason())
                .isEqualTo(RegistrationException.Reason.USERNAME_TAKEN);
    }

    // 注：注解元素必须是编译期常量，故 21 个 a 以字面量书写（原计划文本的 "a".repeat(21) 无法编译）
    @ParameterizedTest
    @ValueSource(strings = {"ab", "aaaaaaaaaaaaaaaaaaaaa", "bad-name!", "na me", "中文用户"})
    void shouldRejectInvalidUsername(String username) {
        assertThatThrownBy(() -> userService.register(username, "Passw0rd1"))
                .isInstanceOf(RegistrationException.class)
                .extracting(e -> ((RegistrationException) e).reason())
                .isEqualTo(RegistrationException.Reason.USERNAME_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"short1", "allletteronly", "12345678", "Sh0rt!"})
    void shouldRejectWeakPassword(String password) {
        assertThatThrownBy(() -> userService.register("carol", password))
                .isInstanceOf(RegistrationException.class)
                .extracting(e -> ((RegistrationException) e).reason())
                .isEqualTo(RegistrationException.Reason.PASSWORD_WEAK);
    }
}
