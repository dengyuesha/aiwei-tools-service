package com.aiwei.tools.media;

import com.aiwei.tools.execution.ToolExecutionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 海搜免费额度持久化门禁测试。
 */
class HaisouQuotaGuardTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void persistsUsageAcrossRestartAndRejectsBeyondLimit() {
        Path quotaFile = temporaryDirectory.resolve("quota.json");
        HaisouProperties properties = new HaisouProperties(
                "test-key",
                "https://apiok.us/api/b9d1/search",
                "https://apiok.us/api/b9d1/validate",
                1000,
                2,
                "UTC",
                quotaFile);
        Clock clock = Clock.fixed(Instant.parse("2026-08-04T08:00:00Z"), ZoneOffset.UTC);

        HaisouQuotaGuard firstProcess = new HaisouQuotaGuard(properties, new ObjectMapper(), clock);
        assertThat(firstProcess.reserve()).isEqualTo(1);

        HaisouQuotaGuard restartedProcess = new HaisouQuotaGuard(properties, new ObjectMapper(), clock);
        assertThat(restartedProcess.reserve()).isEqualTo(2);
        assertThatThrownBy(restartedProcess::reserve)
                .isInstanceOf(ToolExecutionException.class)
                .extracting(error -> ((ToolExecutionException) error).code())
                .isEqualTo("HAISOU_FREE_QUOTA_EXHAUSTED");
    }
}
