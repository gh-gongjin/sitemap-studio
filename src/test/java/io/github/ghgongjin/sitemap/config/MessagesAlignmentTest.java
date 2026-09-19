package io.github.ghgongjin.sitemap.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName MessagesAlignmentTest
 * @Description i18n 资源门禁：zh 基线与 en 键集合完全一致、值非空、{n} 占位符集合一致（flash 键经 MessageSource 解析，缺一角即页面裸键）
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
class MessagesAlignmentTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)");

    @Test
    void shouldHaveIdenticalKeySets() throws Exception {
        Properties zh = load("messages.properties");
        Properties en = load("messages_en.properties");
        Set<String> zhKeys = keys(zh);
        Set<String> enKeys = keys(en);
        Set<String> onlyZh = new TreeSet<>(zhKeys);
        onlyZh.removeAll(enKeys);
        Set<String> onlyEn = new TreeSet<>(enKeys);
        onlyEn.removeAll(zhKeys);
        assertThat(onlyZh).as("仅存在于中文的键").isEmpty();
        assertThat(onlyEn).as("仅存在于英文的键").isEmpty();
    }

    @Test
    void shouldHaveNoBlankValues() throws Exception {
        for (Properties props : List.of(load("messages.properties"), load("messages_en.properties"))) {
            for (String key : keys(props)) {
                assertThat(props.getProperty(key)).as("键 %s 的值为空", key).isNotBlank();
            }
        }
    }

    @Test
    void shouldAlignPlaceholderIndices() throws Exception {
        Properties zh = load("messages.properties");
        Properties en = load("messages_en.properties");
        for (String key : keys(zh)) {
            assertThat(placeholders(en.getProperty(key))).as("键 %s 占位符不一致", key)
                    .isEqualTo(placeholders(zh.getProperty(key)));
        }
    }

    private Properties load(String name) throws Exception {
        Properties props = new Properties();
        try (InputStream in = new ClassPathResource(name).getInputStream()) {
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return props;
    }

    private Set<String> keys(Properties props) {
        return new TreeSet<>(props.stringPropertyNames());
    }

    private Set<String> placeholders(String value) {
        Set<String> out = new HashSet<>();
        Matcher m = PLACEHOLDER.matcher(value == null ? "" : value);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
