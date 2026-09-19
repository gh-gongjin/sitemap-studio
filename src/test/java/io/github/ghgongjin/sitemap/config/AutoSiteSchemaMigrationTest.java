package io.github.ghgongjin.sitemap.config;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.repository.AutoSiteRepository;
import io.github.ghgongjin.sitemap.service.AutoSiteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @ClassName AutoSiteSchemaMigrationTest
 * @Description auto_site 唯一约束迁移：在真实 H2 上验证旧库的 site_url 全局唯一约束被移除后同一 URL
 *              可归属不同用户、同用户重复仍被联合唯一拒绝、联合约束不被误删、重复启动幂等；
 *              并验证约束名挑选规则只认「单列 SITE_URL」
 * @Author gj
 * @Date 2026/9/19
 * @Version 1.0
 */
@DataJpaTest
@Import(AutoSiteSchemaMigration.class)
@ActiveProfiles("test")
class AutoSiteSchemaMigrationTest {

    @Autowired
    private AutoSiteSchemaMigration migration;

    @Autowired
    private AutoSiteRepository siteRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldAllowSameUrlForDifferentUsersWhenLegacyGlobalUniqueMigrated() {
        // Given: 模拟升级前的库——site_url 上挂着全局单列唯一约束
        jdbc.execute("ALTER TABLE auto_site ADD CONSTRAINT UK_LEGACY_A UNIQUE (site_url)");
        assertThat(uniqueConstraintNames()).contains("UK_LEGACY_A");

        // When
        migration.migrate();

        // Then: 旧约束被移除，联合唯一索引就位
        assertThat(uniqueConstraintNames()).doesNotContain("UK_LEGACY_A");
        assertThat(compositeIndexColumns()).containsExactly("USER_ID", "SITE_URL");

        // And: 同一 URL 可以归属两个用户
        AutoSite first = siteRepository.saveAndFlush(site(1L, "https://dup-a.example.com"));
        AutoSite second = siteRepository.saveAndFlush(site(2L, "https://dup-a.example.com"));
        assertThat(second.getId()).isNotNull().isNotEqualTo(first.getId());
    }

    @Test
    void shouldRejectSameUrlForSameUserWhenCompositeIndexApplied() {
        // Given
        migration.migrate();
        siteRepository.saveAndFlush(site(1L, "https://dup-b.example.com"));

        // When & Then: 同用户重复仍被数据库层拒绝
        assertThatThrownBy(() -> siteRepository.saveAndFlush(site(1L, "https://dup-b.example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldKeepExistingCompositeUniqueConstraintWhenMigrating() {
        // Given: 已是 (user_id, site_url) 联合唯一约束的库
        jdbc.execute("ALTER TABLE auto_site ADD CONSTRAINT UK_COMPOSITE_C UNIQUE (user_id, site_url)");

        // When
        migration.migrate();

        // Then: 只删单列全局约束，联合约束原样保留
        assertThat(uniqueConstraintNames()).contains("UK_COMPOSITE_C");
    }

    @Test
    void shouldStayIdempotentWhenMigrationRunsAgain() {
        // Given: 首次迁移已建好联合唯一索引
        migration.migrate();
        List<String> constraintsAfterFirst = uniqueConstraintNames();

        // When: 应用重复启动
        migration.migrate();

        // Then: 约束集合与索引列集合都不变，也不会多出一个同名索引
        assertThat(uniqueConstraintNames()).isEqualTo(constraintsAfterFirst);
        assertThat(compositeIndexColumns()).containsExactly("USER_ID", "SITE_URL");
    }

    @Test
    void shouldSkipDdlWhenAutoSiteTableMissing() {
        // Given: 表不存在的库（例如尚未建表的空实例）
        JdbcTemplate empty = mock(JdbcTemplate.class);
        when(empty.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);

        // When
        new AutoSiteSchemaMigration(empty).migrate();

        // Then: 不执行任何 DDL，避免启动期报「表不存在」
        verify(empty, never()).execute(anyString());
    }

    @Test
    void shouldSelectOnlySingleColumnSiteUrlConstraint() {
        // Given: 单列 SITE_URL 的旧全局约束、联合唯一、以及另一列的单列唯一
        Map<String, Set<String>> columns = Map.of(
                "UK_SITE_URL_ONLY", Set.of("SITE_URL"),
                "UK_COMPOSITE", Set.of("USER_ID", "SITE_URL"),
                "UK_OTHER_COLUMN", Set.of("TASK_ID"));

        // When
        List<String> dropped = AutoSiteSchemaMigration.siteUrlOnlyConstraints(
                List.of("UK_SITE_URL_ONLY", "UK_COMPOSITE", "UK_OTHER_COLUMN", "UK_UNKNOWN"), columns);

        // Then: 只有单列 SITE_URL 约束入选，缺列信息的约束不动
        assertThat(dropped).containsExactly("UK_SITE_URL_ONLY");
    }

    private List<String> uniqueConstraintNames() {
        return jdbc.queryForList("SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = 'AUTO_SITE' AND CONSTRAINT_TYPE = 'UNIQUE' "
                        + "ORDER BY CONSTRAINT_NAME",
                String.class);
    }

    private List<String> compositeIndexColumns() {
        return jdbc.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                        + "WHERE TABLE_NAME = 'AUTO_SITE' AND INDEX_NAME = 'UK_AUTO_SITE_USER_URL' "
                        + "ORDER BY ORDINAL_POSITION",
                String.class);
    }

    private AutoSite site(Long userId, String url) {
        LocalDateTime now = LocalDateTime.now();
        AutoSite site = new AutoSite();
        site.setUserId(userId);
        site.setUrl(url);
        site.setIncludeImages(false);
        site.setIncludeVideos(false);
        site.setIncludeNews(false);
        site.setIntervalHours(24);
        site.setEnabled(true);
        site.setNextRunAt(now);
        site.setLastStatus(AutoSiteService.STATUS_PENDING);
        site.setCreatedAt(now);
        site.setUpdatedAt(now);
        return site;
    }
}
