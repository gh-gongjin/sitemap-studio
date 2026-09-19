package io.github.ghgongjin.sitemap.config;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.repository.AutoSiteRepository;
import io.github.ghgongjin.sitemap.repository.AutoSiteVersionRepository;
import io.github.ghgongjin.sitemap.service.AutoSiteService;
import io.github.ghgongjin.sitemap.service.AutoSiteValidationException;
import io.github.ghgongjin.sitemap.service.CrawlUrlPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
    private AutoSiteVersionRepository versionRepository;

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
    void shouldMapLegacyGlobalUniqueToValidationWhenMigrationDegraded() {
        // Given: 模拟迁移被降级为告警、旧全局 UNIQUE(site_url) 残留的运行期缺口——
        //        联合唯一没建、旧约束还在，用户 A 已托管该 URL
        jdbc.execute("ALTER TABLE auto_site ADD CONSTRAINT UK_LEGACY_E UNIQUE (site_url)");
        AutoSiteService service = new AutoSiteService(siteRepository, versionRepository);
        service.setCrawlUrlPolicy(publicHostPolicy());
        service.create(1L, "https://residual.example.com", false, false, false, 24);

        // When & Then: 用户 B 添加同一 URL——existsByUserIdAndUrl 按 (用户,URL) 判不出冲突，
        //              save 在库层撞残留全局唯一，应归一为 AutoSiteValidationException(duplicate)
        //              而非 DataIntegrityViolationException 裸冒到 whitelabel 500
        assertThatThrownBy(() -> service.create(2L, "https://residual.example.com", false, false, false, 24))
                .isNotInstanceOf(DataIntegrityViolationException.class)
                .isInstanceOfSatisfying(AutoSiteValidationException.class, error -> {
                    assertThat(error.messageKey()).isEqualTo("auto.error.duplicate");
                    assertThat(error.args()).containsExactly("https://residual.example.com");
                });
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
    void shouldStayReadyWhenEveryDdlStatementFails() {
        // Given: 旧库确实挂着待删的单列唯一约束，但所有 DDL 执行都会失败
        //        （例如账号无 DDL 权限、表被别的连接锁住）
        jdbc.execute("ALTER TABLE auto_site ADD CONSTRAINT UK_LEGACY_D UNIQUE (site_url)");
        CountingJdbcTemplate brokenDdl = new CountingJdbcTemplate(
                Objects.requireNonNull(jdbc.getDataSource()));

        // When: 迁移被调用，两条 DDL 都抛 DataAccessException
        assertThatCode(() -> new AutoSiteSchemaMigration(brokenDdl).migrate())
                .doesNotThrowAnyException();

        // Then: 异常被逐条降级吞掉，不打断 ApplicationReadyEvent，应用保持就绪
        //  And: 两条 DDL 各自独立尝试（删约束失败仍继续建联合唯一索引）
        assertThat(brokenDdl.attempts).isEqualTo(2);
        // And: 隔离语义不依赖该 DDL：归属过滤在应用层，旧约束残留只是少了一层库内兜底
        assertThat(uniqueConstraintNames()).contains("UK_LEGACY_D");
        assertThat(siteRepository.findByUserIdOrderByCreatedAtDesc(1L)).isEmpty();
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

    /**
     * 真实 DataSource 上的 JdbcTemplate 替身：SELECT 查询走真实库（约束发现逻辑仍被真实数据检验），
     * 只把 DDL 执行改成必然失败，并记录尝试次数
     */
    private static class CountingJdbcTemplate extends JdbcTemplate {

        private int attempts;

        CountingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public void execute(String sql) throws DataAccessException {
            attempts++;
            throw new BadSqlGrammarException("execute", sql, new SQLException("模拟 DDL 失败"));
        }
    }

    /**
     * 放行任意主机的策略桩：只保留 URL 解析，不做真实 DNS，便于跨包构造 AutoSiteService
     */
    private CrawlUrlPolicy publicHostPolicy() {
        return new AnyHostPolicy();
    }

    private static class AnyHostPolicy extends CrawlUrlPolicy {

        @Override
        public URI validate(String url) {
            return URI.create(url.trim());
        }

        @Override
        public URI validate(String url, String scopeBase) {
            return validate(url);
        }
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
