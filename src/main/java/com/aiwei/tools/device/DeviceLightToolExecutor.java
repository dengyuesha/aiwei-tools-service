package com.aiwei.tools.device;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 生成标准灯光设备命令；实际下发仍由调用方设备链路完成。
 */
@Component
public class DeviceLightToolExecutor implements ToolExecutor {

    @Override
    public String toolName() {
        return "device.light.set";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        String state = String.valueOf(request.arguments().getOrDefault("state", "")).trim().toLowerCase();
        if (!"on".equals(state) && !"off".equals(state)) {
            throw new ToolExecutionException("INVALID_STATE", "state must be on or off",
                    false, "灯光状态只能是打开或关闭。");
        }
        String scene = String.valueOf(request.arguments().getOrDefault("scene", "normal")).trim();
        Map<String, Object> command = Map.of(
                "type", "device_command",
                "capability", "light",
                "action", "set",
                "state", state,
                "scene", scene,
                "requires_caller_dispatch", true);
        return new ToolExecutionResult("device_command_contract",
                "on".equals(state) ? "已生成开灯命令。" : "已生成关灯命令。",
                command, false);
    }
}
