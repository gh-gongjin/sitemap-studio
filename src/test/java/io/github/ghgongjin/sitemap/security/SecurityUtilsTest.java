package io.github.ghgongjin.sitemap.security;

import io.github.ghgongjin.sitemap.entity.UserAccount;
import io.github.ghgongjin.sitemap.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName SecurityUtilsTest
 * @Description SecurityUtils 契约测试：登录态返回真实注册用户 id/用户名，匿名与无上下文返回 null/空串。
 *              Authentication 一律基于真实注册（register + loadUserByUsername）的用户数据构造
 * @Author gj
 * @Date 2026/9/19
 * @Version 1.0
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SecurityUtilsTest {

    @Autowired
    private UserService userService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldReturnRegisteredUserIdAndUsernameWhenAuthenticated() {
        UserAccount saved = userService.register("alice", "Passw0rd1");
        UserAccountDetails details = (UserAccountDetails) userService.loadUserByUsername("alice");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                details, null, details.getAuthorities()));

        assertThat(details.id()).isEqualTo(saved.getId());
        assertThat(SecurityUtils.currentUserId()).isEqualTo(saved.getId());
        assertThat(SecurityUtils.currentUsername()).isEqualTo("alice");
    }

    @Test
    void shouldReturnEmptyAndNullWhenAnonymous() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(SecurityUtils.currentUsername()).isEmpty();
        assertThat(SecurityUtils.currentUserId()).isNull();
    }

    @Test
    void shouldReturnEmptyAndNullWhenNoAuthentication() {
        assertThat(SecurityUtils.currentUsername()).isEmpty();
        assertThat(SecurityUtils.currentUserId()).isNull();
    }
}
