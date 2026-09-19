package io.github.ghgongjin.sitemap.service;

import io.github.ghgongjin.sitemap.entity.UserAccount;
import io.github.ghgongjin.sitemap.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * @ClassName UserService
 * @Description 用户注册与认证信息查询：注册做用户名/密码强度校验与 BCrypt 加密，
 *              同时作为 Spring Security 的 UserDetailsService 供 formLogin 使用
 * @Author gj
 * @Date 2026/9/19
 * @Version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private static final Pattern USERNAME = Pattern.compile("^\\w{3,20}$");
    // 8-64 位、同时包含字母与数字（预查式写法，顺序不限）
    private static final Pattern STRONG_PASSWORD =
            Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)\\S{8,64}$");

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserAccount register(String username, String rawPassword) {
        String name = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(name).matches()) {
            throw new RegistrationException(RegistrationException.Reason.USERNAME_INVALID);
        }
        if (rawPassword == null || !STRONG_PASSWORD.matcher(rawPassword).matches()) {
            throw new RegistrationException(RegistrationException.Reason.PASSWORD_WEAK);
        }
        if (userAccountRepository.existsByUsername(name)) {
            throw new RegistrationException(RegistrationException.Reason.USERNAME_TAKEN);
        }
        UserAccount account = new UserAccount();
        account.setUsername(name);
        account.setPasswordHash(passwordEncoder.encode(rawPassword));
        account.setCreatedAt(LocalDateTime.now());
        UserAccount saved = userAccountRepository.save(account);
        log.info("新用户注册成功：{}", name);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount account = userAccountRepository.findByUsername(
                        username == null ? "" : username.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
        return User.withUsername(account.getUsername())
                .password(account.getPasswordHash())
                .roles("USER")
                .build();
    }
}
