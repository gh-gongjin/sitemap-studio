package io.github.ghgongjin.sitemap.service;

import io.github.ghgongjin.sitemap.entity.UserAccount;
import io.github.ghgongjin.sitemap.repository.UserAccountRepository;
import io.github.ghgongjin.sitemap.security.UserAccountDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
        UserAccount saved;
        try {
            saved = userAccountRepository.save(account);
        } catch (DataIntegrityViolationException e) {
            // existsByUsername 与 save 之间非原子：并发/双击同名提交时唯一索引冲突在此归一为
            // USERNAME_TAKEN，避免原始异常越过控制器变成 whitelabel 500
            throw new RegistrationException(RegistrationException.Reason.USERNAME_TAKEN);
        }
        // IDENTITY 主键策略下 save() 会立即执行 INSERT，能走到这里说明行已写入数据库；
        // 事务提交阶段仍有极小概率失败（如连接中断），接受该残余风险，不额外引入 afterCommit 回调
        log.info("新用户注册成功：{}", name);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount account = userAccountRepository.findByUsername(
                        username == null ? "" : username.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
        // 返回携带用户 id 的自定义 UserDetails，供 SecurityUtils 与后续业务隔离使用
        return new UserAccountDetails(account.getId(), account.getUsername(), account.getPasswordHash());
    }
}
