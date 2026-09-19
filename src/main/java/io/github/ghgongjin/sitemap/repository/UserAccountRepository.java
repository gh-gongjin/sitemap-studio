package io.github.ghgongjin.sitemap.repository;

import io.github.ghgongjin.sitemap.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * @ClassName UserAccountRepository
 * @Description 用户账号仓储（按登录名查询/判重）
 * @Author gj
 * @Date 2026/9/19
 * @Version 1.0
 */
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByUsername(String username);

    boolean existsByUsername(String username);
}
