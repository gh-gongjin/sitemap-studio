package io.github.ghgongjin.sitemap.service;

import io.github.ghgongjin.sitemap.entity.UserAccount;
import io.github.ghgongjin.sitemap.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Optional;

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
        // 注册结果真实持久化：可通过 repository 按用户名查回，且散列已落库
        Optional<UserAccount> stored = repository.findByUsername("alice");
        assertThat(stored).isPresent();
        assertThat(stored.get().getPasswordHash()).isEqualTo(saved.getPasswordHash());
    }

    @Test
    void shouldMapUniqueIndexViolationToUsernameTakenWhenRaceLost() {
        // Given：模拟判重竞态——existsByUsername 检查通过后、save 之前另一事务插入同名用户，
        // 唯一索引冲突使 save 抛 DataIntegrityViolationException（Mock 构造该分支，无需真实并发）
        UserAccountRepository racyRepository = Mockito.mock(UserAccountRepository.class);
        Mockito.when(racyRepository.existsByUsername("dave")).thenReturn(false);
        Mockito.when(racyRepository.save(Mockito.any(UserAccount.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "Unique index or primary key violation"));
        UserService racyUserService = new UserService(racyRepository, passwordEncoder);

        // When & Then：竞态失败必须归一为 USERNAME_TAKEN，原始异常不得越层成为 whitelabel 500
        assertThatThrownBy(() -> racyUserService.register("dave", "Passw0rd1"))
                .isInstanceOf(RegistrationException.class)
                .extracting(e -> ((RegistrationException) e).reason())
                .isEqualTo(RegistrationException.Reason.USERNAME_TAKEN);
    }

    @Test
    void shouldRejectDuplicateUsernameAtDatabaseWhenApplicationCheckBypassed() {
        // Given / When：绕过应用层判重，直接向 repository 插入同名实体
        repository.saveAndFlush(buildAccount("erin"));

        // Then：数据库唯一索引是竞态下的最后防线，冲突应抛 DataIntegrityViolationException
        assertThatThrownBy(() -> repository.saveAndFlush(buildAccount("erin")))
                .isInstanceOf(DataIntegrityViolationException.class);
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

    private static UserAccount buildAccount(String username) {
        UserAccount account = new UserAccount();
        account.setUsername(username);
        account.setPasswordHash("{bcrypt}$2a$placeholder");
        account.setCreatedAt(LocalDateTime.now());
        return account;
    }
}
