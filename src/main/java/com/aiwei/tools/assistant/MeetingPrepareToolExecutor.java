package com.aiwei.tools.assistant;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 根据会议主题生成可执行的会议准备清单。
 */
@Component
public class MeetingPrepareToolExecutor implements ToolExecutor {

    @Override
    public String toolName() {
        return "meeting.prepare";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        Map<String, Object> args = request.arguments();
        String topic = text(args.getOrDefault("topic", "会议准备"));
        String audience = text(args.getOrDefault("audience", ""));
        List<Map<String, Object>> agenda = List.of(
                Map.of("time", "00-05", "title", "确认目标", "detail", "对齐目标、范围和成功标准。"),
                Map.of("time", "05-20", "title", "讨论方案", "detail", "围绕关键事实、选项和约束展开。"),
                Map.of("time", "20-30", "title", "确认行动", "detail", "明确负责人、截止时间和验收方式。"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "meeting_brief");
        data.put("title", topic);
        data.put("audience", audience);
        data.put("goal", audience.contains("客户")
                ? "确认客户目标、方案价值与下一步推进方式。"
                : "对齐目标、形成决策并落实后续行动。");
        data.put("agenda", agenda);
        data.put("materials", List.of("背景与关键数据", "待决策事项", "风险及备选方案"));
        data.put("next_actions", List.of("会后发送纪要", "登记负责人和截止时间"));
        return new ToolExecutionResult("builtin_meeting_planner",
                topic + "已准备好，包含目标、议程、材料和会后行动。", data, false);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
