package io.github.ghgongjin.sitemap.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * @ClassName SecurityUtils
 * @Description 当前登录用户读取工具：无会话/匿名时 currentUserId 返回 null、currentUsername 返回空串，
 *              供页面展示与后续业务隔离使用
 * @Author gj
 * @Date 2026/9/19
 * @Version 1.0
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof UserAccountDetails details)) {
            return null;
        }
        return details.id();
    }

    public static String currentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        // AnonymousAuthenticationToken 也是 authenticated()==true，其 name 为 anonymousUser，
        // 按约定（匿名返回 ""）需显式排除
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return "";
        }
        return auth.getName();
    }
}
