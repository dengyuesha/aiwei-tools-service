package com.aiwei.tools.device;

import com.aiwei.tools.contract.ToolContext;
import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设备灯光命令契约测试。
 */
class DeviceLightToolExecutorTest {

    @Test
    void returnsCallerDispatchCommandInsteadOfMutatingSession() {
        ToolInvokeRequest request = new ToolInvokeRequest(
                "req-light", "tenant", "user", "session",
                Map.of("state", "on", "scene", "night"), ToolContext.empty(), "idem-light");

        ToolExecutionResult result = new DeviceLightToolExecutor().execute(request);

        assertThat(result.provider()).isEqualTo("device_command_contract");
        assertThat(result.data()).containsEntry("requires_caller_dispatch", true);
    }
}
