package io.github.ghgongjin.sitemap.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName SubmissionSchemaDefaultsTest
 * @Description 搜索引擎提交数据底座 schema 守卫：存量表 push_config 加 NOT NULL 列必须带库级默认值
 *              （skipped_pages 500 教训）——用新库 INFORMATION_SCHEMA 列定义等价断言旧库升级 ALTER
 *              （照 SeoReportSchemaDefaultsTest 模式），并守卫 submission_log 表随 JPA 引导建出。
 * @Author gj
 * @Date 2026/9/21
 * @Version 1.0
 */
@DataJpaTest
@ActiveProfiles("test")
class SubmissionSchemaDefaultsTest {

    @Autowired
    private JdbcTemplate jdbc;

    private String columnDefault(String table, String column) {
        return jdbc.queryForObject(
                "SELECT COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE UPPER(TABLE_NAME) = ? AND UPPER(COLUMN_NAME) = ?",
                String.class, table, column);
    }

    @Test
    void shouldCarryDefaultFalseOnBaiduEnabledWhenAddingColumnToLegacyTable() {
        assertThat(columnDefault("PUSH_CONFIG", "BAIDU_ENABLED")).matches("(?i)FALSE|0");
    }

    @Test
    void shouldCarryDefaultFalseOnGscEnabledWhenAddingColumnToLegacyTable() {
        assertThat(columnDefault("PUSH_CONFIG", "GSC_ENABLED")).matches("(?i)FALSE|0");
    }

    @Test
    void shouldCreateSubmissionLogTableWhenJpaBootstrap() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = 'SUBMISSION_LOG'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
