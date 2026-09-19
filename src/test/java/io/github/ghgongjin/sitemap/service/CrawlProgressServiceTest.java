package io.github.ghgongjin.sitemap.service;

import io.github.ghgongjin.sitemap.service.CrawlProgressService.TaskResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CrawlProgressServiceTest {

    private static final String URL = "https://original.example/site";
    private static final String XML = "<urlset/>";
    private CrawlProgressService progress;
    private SimpMessagingTemplate messages;

    @BeforeEach
    void setUp() {
        messages = mock(SimpMessagingTemplate.class);
        progress = new CrawlProgressService(messages);
    }

    @ParameterizedTest
    @ValueSource(strings = {"running", "completed", "failed"})
    void shouldKeepOriginalTaskWhenLegacyStartIsRepeated(String state) {
        // Given
        progress.startTask("task", URL);
        if ("completed".equals(state)) {
            progress.completeTask("task", 3, XML);
        } else if ("failed".equals(state)) {
            progress.failTask("task", "simulated failure");
        }
        TaskResult original = progress.getTaskResult("task");

        // When
        progress.startTask("task", "https://replacement.example/");

        // Then
        TaskResult current = progress.getTaskResult("task");
        assertThat(current).isSameAs(original);
        assertThat(current.getUrl()).isEqualTo(URL);
        assertThat(current.getStatus()).isEqualTo(state);
        if ("completed".equals(state)) {
            assertThat(current.getSitemapXml()).isEqualTo(XML);
        } else if ("failed".equals(state)) {
            assertThat(current.getErrorMessage()).isEqualTo("simulated failure");
        }
    }

    @Test
    void shouldKeepProgressAndAvoidDuplicateStartedEventWhenStartIsRepeated() {
        // Given
        progress.startTask("task", URL);
        progress.updateProgress("task", 7, 10, URL + "/page");
        CrawlProgressService.ProgressInfo original = progress.getProgress("task");

        // When
        progress.startTask("task", URL);

        // Then
        assertThat(progress.getProgress("task")).isSameAs(original);
        assertThat(progress.getProgress("task").getCrawledPages()).isEqualTo(7);
        // One started event and one progress event; the duplicate start sends nothing.
        verify(messages, times(2)).convertAndSend(eq("/topic/progress/task"), any(Object.class));
    }

    @Test
    void shouldStoreMediaOptionsAsImmutableTaskFields() {
        // Given / When: options must be immutable, not mutable request state.
        List<String> immutableBooleans = Arrays.stream(TaskResult.class.getDeclaredFields())
                .filter(field -> field.getType() == boolean.class && Modifier.isFinal(field.getModifiers()))
                .map(java.lang.reflect.Field::getName).toList();

        // Then
        assertThat(immutableBooleans).contains("includeImages", "includeVideos");
    }

    @ParameterizedTest
    @ValueSource(strings = {"markCompleted", "markFailed"})
    void shouldPublishVolatileStatusAfterPayloadWritesWhenTaskBecomesTerminal(String methodName)
            throws Exception {
        // Given: inspect actual compiled field-write order to make this race regression deterministic.
        // A timing-only stress test could pass on the broken implementation depending on scheduling.
        List<String> writes = new ArrayList<>();
        try (InputStream bytecode = TaskResult.class.getResourceAsStream("CrawlProgressService$TaskResult.class")) {
            assertThat(bytecode).isNotNull();
            ClassReader reader = new ClassReader(bytecode);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (!methodName.equals(name)) {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            if (opcode == Opcodes.PUTFIELD) {
                                writes.add(name);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }

        // When / Then: the final volatile write publishes every payload write to readers.
        assertThat(Modifier.isVolatile(TaskResult.class.getDeclaredField("status").getModifiers())).isTrue();
        assertThat(writes).endsWith("status");
        assertThat(writes).contains("crawledPages");
        assertThat(writes).contains("markCompleted".equals(methodName) ? "sitemapXml" : "errorMessage");
    }

    @Test
    void shouldExposeCompletePayloadWhenTaskCompletes() {
        // Given
        progress.startTask("task", URL);

        // When
        progress.completeTask("task", 3, XML);

        // Then
        TaskResult result = progress.getTaskResult("task");
        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(result.getSitemapXml()).isEqualTo(XML);
        assertThat(result.getTotalPages()).isEqualTo(3);
        assertThat(result.getCrawledPages()).isEqualTo(3);
        assertThat(progress.getProgress("task")).isNull();
    }
}
