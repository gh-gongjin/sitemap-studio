package io.github.ghgongjin.sitemap.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * @ClassName UserAccountDetails
 * @Description UserDetails 实现：在认证主体中携带用户 id，供 SecurityUtils.currentUserId 与
 *              后续业务隔离（Task 3/4）使用；当前角色统一为 ROLE_USER
 * @Author gj
 * @Date 2026/9/19
 * @Version 1.0
 */
public record UserAccountDetails(Long id, String username, String passwordHash)
        implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
