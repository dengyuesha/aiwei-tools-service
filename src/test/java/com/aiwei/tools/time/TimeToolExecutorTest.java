package com.aiwei.tools.time;

import com.aiwei.tools.contract.ToolContext;
import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TimeToolExecutorTest {

    @Test
    void speaksChineseClockWithExplicitHourAndMinuteUnits() {
        ToolExecutionResult result = new TimeToolExecutor().execute(new ToolInvokeRequest(
                "req-time-spoken",
                "default",
                "user",
                "session",
                Map.of("timezone", "Asia/Shanghai", "focus", "time"),
                ToolContext.empty(),
                null));

        assertThat(result.summary())
                .matches("现在北京时间是\\d{1,2}时(?:整|\\d{1,2}分)。");
    }
}
