package io.github.ghgongjin.sitemap.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @ClassName SiteDiffEngineTest
 * @Description 版本间 URL 集合差异纯函数：增删改语义、重复 loc、空/缺 XML 边界、千级性能冒烟
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
class SiteDiffEngineTest {

    private static String urlset(String... urlAndLastmodPairs) {
        StringBuilder sb = new StringBuilder("<urlset>");
        for (int i = 0; i < urlAndLastmodPairs.length; i += 2) {
            sb.append("<url><loc>").append(urlAndLastmodPairs[i])
                    .append("</loc><lastmod>").append(urlAndLastmodPairs[i + 1])
                    .append("</lastmod></url>");
        }
        return sb.append("</urlset>").toString();
    }

    static Stream<Arguments> diffMatrix() {
        return Stream.of(
                // 名称 / 旧版 / 新版 / added / removed / changed
                Arguments.of("addedOnly",
                        urlset("https://a/x", "2026-01-01"),
                        urlset("https://a/x", "2026-01-01", "https://a/y", "2026-01-02"),
                        List.of("https://a/y"), List.of(), List.of()),
                Arguments.of("removedOnly",
                        urlset("https://a/x", "2026-01-01", "https://a/y", "2026-01-01"),
                        urlset("https://a/x", "2026-01-01"),
                        List.of(), List.of("https://a/y"), List.of()),
                Arguments.of("changedByLastmod",
                        urlset("https://a/x", "2026-01-01"),
                        urlset("https://a/x", "2026-02-02"),
                        List.of(), List.of(), List.of("https://a/x")),
                Arguments.of("identicalYieldsEmpty",
                        urlset("https://a/x", "2026-01-01"),
                        urlset("https://a/x", "2026-01-01"),
                        List.of(), List.of(), List.of()),
                Arguments.of("missingLastmodTreatedAsEmptyString",
                        "<urlset><url><loc>https://a/x</loc></url></urlset>",
                        urlset("https://a/x", "2026-01-01"),
                        List.of(), List.of(), List.of("https://a/x")),
                Arguments.of("locIsCaseSensitive",
                        urlset("https://a/X", "2026-01-01"),
                        urlset("https://a/x", "2026-01-01"),
                        List.of("https://a/x"), List.of("https://a/X"), List.of()),
                Arguments.of("emptyXmlMeansAllRemoved",
                        urlset("https://a/x", "2026-01-01"),
                        "<urlset></urlset>",
                        List.of(), List.of("https://a/x"), List.of()),
                Arguments.of("sortedOutputs",
                        urlset("https://a/z", "2026-01-01"),
                        urlset("https://a/z", "2026-01-01", "https://a/b", "2026-01-01", "https://a/m", "2026-01-01"),
                        List.of("https://a/b", "https://a/m"), List.of(), List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("diffMatrix")
    void shouldComputeDiff(String name, String oldXml, String newXml,
                           List<String> added, List<String> removed, List<String> changed) {
        SiteDiffEngine.SiteDiff diff = SiteDiffEngine.diff(oldXml, newXml);
        assertThat(diff.added()).as(name + ".added").containsExactlyElementsOf(added);
        assertThat(diff.removed()).as(name + ".removed").containsExactlyElementsOf(removed);
        assertThat(diff.changed()).as(name + ".changed").containsExactlyElementsOf(changed);
    }

    @Test
    void shouldLetDuplicateLocKeepLastLastmod() {
        String oldXml = urlset("https://a/x", "2026-01-01", "https://a/x", "2026-03-03");
        String newXml = urlset("https://a/x", "2026-03-03");
        assertThat(SiteDiffEngine.diff(oldXml, newXml).changed()).isEmpty();
    }

    @Test
    void shouldThrowWhenEitherXmlMissing() {
        assertThatThrownBy(() -> SiteDiffEngine.diff(null, urlset("https://a/x", "2026-01-01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SiteDiffEngine.diff(urlset("https://a/x", "2026-01-01"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldDiffOneThousandUrlsWithinOneSecond() {
        List<String> oldPairs = new ArrayList<>();
        List<String> newPairs = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            oldPairs.add("https://a/p" + i);
            oldPairs.add("2026-01-01");
            newPairs.add("https://a/p" + i);
            newPairs.add(i % 2 == 0 ? "2026-01-01" : "2026-02-02");
        }
        newPairs.add("https://a/new");
        newPairs.add("2026-02-02");
        long start = System.nanoTime();
        SiteDiffEngine.SiteDiff diff = SiteDiffEngine.diff(urlset(oldPairs.toArray(String[]::new)),
                urlset(newPairs.toArray(String[]::new)));
        assertThat(System.nanoTime() - start).isLessThan(1_000_000_000L);
        assertThat(diff.changed()).hasSize(500);
        assertThat(diff.added()).containsExactly("https://a/new");
        assertThat(diff.total()).isEqualTo(501);
    }
}
