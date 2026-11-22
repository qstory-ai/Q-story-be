package com.qstory.backend.common.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Grafana Terraform이 LogQL로 잡는 로그 이벤트 이름이 전부 LogEvents에 있는지 본다 - 자바 쪽에서 이름을 바꾸면 알림이 조용히
 * 멈추는 것을 막는다. ops/ 폴더가 없는 곳(복사본에서 돌릴 때)은 건너뛴다.
 */
class LogEventsGrafanaContractTest {

    private static final Pattern FILTER = Pattern.compile("\\|[=~] *\\\\\"([^\\\\]+)\\\\\"");
    private static final Pattern EVENT_NAME = Pattern.compile("^[a-z][a-z0-9-]*\\.[a-z][a-z0-9.-]*$");

    @Test
    void everyEventNameInTerraformIsAJavaConstant() throws IOException {
        Path ops = Path.of("ops", "grafana-alerting");
        assumeTrue(Files.isDirectory(ops), "ops/grafana-alerting 없음 - 건너뜀");
        TreeSet<String> referenced = new TreeSet<>();
        try (Stream<Path> files = Files.list(ops)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".tf")).toList()) {
                Matcher matcher = FILTER.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    for (String candidate : List.of(matcher.group(1).split("\\|"))) {
                        String name = candidate.replace("[.]", ".");
                        if (EVENT_NAME.matcher(name).matches()) referenced.add(name);
                    }
                }
            }
        }
        assertThat(referenced).isNotEmpty();
        assertThat(LogEvents.ALL).containsAll(referenced);
    }
}
