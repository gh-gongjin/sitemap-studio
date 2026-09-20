package io.github.ghgongjin.sitemap.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName SeoReportSchemaDefaultsTest
 * @Description seo_report 标量列 DDL 默认值守卫：存量表加 NOT NULL 列时 H2 会用 DEFAULT 回填旧行，
 *              缺默认值的 ALTER 在旧库升级期静默失败（Hibernate 只记日志不中断），运行期查询才炸 500。
 *              Hibernate create 与 update 模式对同一列输出相同的 DDL 片段，故用新建库的
 *              INFORMATION_SCHEMA 列定义断言等价于验证升级 ALTER。
 * @Author gj
 * @Date 2026/9/20
 * @Version 1.0
 */
@DataJpaTest
@ActiveProfiles("test")
class SeoReportSchemaDefaultsTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldCarryDefaultZeroOnSkippedPagesWhenAddingColumnToPopulatedLegacyTable() {
        // Given: seo_report 存量行场景（升级 ALTER 必须先有默认值可回填）

        // When: Hibernate 生成的建表 DDL 落库后读回列定义
        String columnDefault = jdbc.queryForObject(
                "SELECT COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE UPPER(TABLE_NAME) = 'SEO_REPORT' AND UPPER(COLUMN_NAME) = 'SKIPPED_PAGES'",
                String.class);

        // Then: 列自带 DEFAULT 0——ddl-auto=update 对旧库执行的 ADD COLUMN 使用同一份定义
        assertThat(columnDefault).isEqualTo("0");
    }
}
