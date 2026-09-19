package io.github.ghgongjin.sitemap.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * @ClassName AutoSiteSchemaMigration
 * @Description auto_site 唯一约束迁移：site_url 全局唯一 → (user_id, site_url) 联合唯一。
 *              ddl-auto=update 不会删除历史遗留的单列唯一约束，故在应用就绪后从 H2
 *              INFORMATION_SCHEMA 动态发现「仅由 SITE_URL 一列构成」的唯一约束并删除，
 *              再幂等创建联合唯一索引（约束名由 Hibernate 生成，不可硬编码）。
 *              两条 DDL 逐条 try/catch 降级为 ERROR：任一失败都不打断 ApplicationReadyEvent，
 *              应用保持就绪（用户隔离由应用层归属校验保证，库层唯一只是兜底，下次启动自愈）；
 *              但失败会以 ERROR 响亮记录，便于运维发现——旧全局唯一残留时应用层 create
 *              已把冲突归一为「该网站已在列表中」，不再裸冒 whitelabel 500
 * @Author gj
 * @Date 2026/9/19
 * @Version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoSiteSchemaMigration {

    static final String TABLE_NAME = "AUTO_SITE";
    static final String SITE_URL_COLUMN = "SITE_URL";
    static final String USER_URL_INDEX_NAME = "uk_auto_site_user_url";

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        if (!tableExists()) {
            log.warn("auto_site 表不存在，跳过唯一约束迁移");
            return;
        }
        // 先删旧的全局唯一约束，否则同一 URL 无法归属多个用户
        for (String name : findLegacySiteUrlConstraints()) {
            dropLegacyConstraint(name);
        }
        // 联合唯一索引不属于 TABLE_CONSTRAINTS，重复启动时 IF NOT EXISTS 保证幂等
        createCompositeIndex();
    }

    /**
     * DDL 逐条降级：约束名来自 INFORMATION_SCHEMA，正常必然可删；残留竞态（如并发已删）或
     * 权限不足时以 ERROR 响亮记录（旧全局唯一残留是运维需知的运行期缺口），但不打断应用就绪
     */
    private void dropLegacyConstraint(String name) {
        try {
            jdbc.execute("ALTER TABLE auto_site DROP CONSTRAINT \"" + name + "\"");
            log.info("已移除 auto_site.site_url 全局唯一约束：{}", name);
        } catch (DataAccessException e) {
            log.error("移除 auto_site.site_url 全局唯一约束失败，跳过并等待下次启动重试：{}，原因：{}",
                    name, e.getMessage());
        }
    }

    /**
     * 联合唯一索引同理：库层唯一兜底建不起来也不打断 ApplicationReadyEvent，
     * 用户隔离语义由应用层归属校验保证，下次启动自愈；失败以 ERROR 记录便于运维发现
     */
    private void createCompositeIndex() {
        try {
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + USER_URL_INDEX_NAME
                    + " ON auto_site(user_id, site_url)");
        } catch (DataAccessException e) {
            log.error("创建 auto_site(user_id, site_url) 联合唯一索引失败，跳过并等待下次启动重试：{}",
                    e.getMessage());
        }
    }

    private boolean tableExists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?",
                Integer.class, TABLE_NAME);
        return count != null && count > 0;
    }

    /**
     * 两步查询代替相关子查询（H2 对 NOT IN 子查询支持有限）：
     * 先取表上的 UNIQUE 约束名，再取约束列，交由 siteUrlOnlyConstraints 判定单列 SITE_URL
     */
    private List<String> findLegacySiteUrlConstraints() {
        List<String> uniqueNames = jdbc.queryForList(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = ? AND CONSTRAINT_TYPE = 'UNIQUE'",
                String.class, TABLE_NAME);
        if (uniqueNames.isEmpty()) {
            return List.of();
        }
        Map<String, Set<String>> columnsByConstraint = new TreeMap<>();
        jdbc.query("SELECT CONSTRAINT_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.CONSTRAINT_COLUMN_USAGE "
                        + "WHERE TABLE_NAME = ?",
                rs -> {
                    String constraint = rs.getString(1);
                    String column = rs.getString(2).toUpperCase(Locale.ROOT);
                    columnsByConstraint
                            .computeIfAbsent(constraint, key -> new TreeSet<>())
                            .add(column);
                },
                TABLE_NAME);
        return siteUrlOnlyConstraints(uniqueNames, columnsByConstraint);
    }

    /**
     * 只保留「唯一约束且列集合恰好为 {SITE_URL}」的约束名，避免误删联合唯一约束
     */
    static List<String> siteUrlOnlyConstraints(List<String> uniqueConstraintNames,
                                               Map<String, Set<String>> columnsByConstraint) {
        return uniqueConstraintNames.stream()
                .filter(name -> {
                    Set<String> columns = columnsByConstraint.getOrDefault(name, Set.of());
                    return columns.size() == 1 && columns.contains(SITE_URL_COLUMN);
                })
                .toList();
    }
}
