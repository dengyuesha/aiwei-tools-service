package com.aiwei.tools.assistant;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成不依赖地图和用户会话的基础行程计划。
 */
@Component
public class ItineraryPlanToolExecutor implements ToolExecutor {

    @Override
    public String toolName() {
        return "itinerary.plan";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        Map<String, Object> args = request.arguments();
        String city = text(args.get("city"));
        if (city.isBlank()) {
            throw new ToolExecutionException("CITY_REQUIRED", "city is required",
                    false, "请说明要在哪个城市规划行程。");
        }
        String date = text(args.getOrDefault("date", "today"));
        String theme = text(args.getOrDefault("theme", "balanced"));
        boolean business = "business".equalsIgnoreCase(theme) || "商务".equals(theme);
        List<Map<String, Object>> steps = List.of(
                step("09:00", business ? "确认当天商务目标" : "处理当天重点事项", city, "focus"),
                step("11:30", "核对路线、天气和随身物品", city, "prepare"),
                step("14:00", business ? "客户或方案沟通" : "集中处理外出事项", city, "activity"),
                step("17:30", "返程并记录当天结果", city, "review"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "itinerary_plan");
        data.put("city", city);
        data.put("date", date);
        data.put("theme", theme);
        data.put("steps", steps);
        data.put("reminders", List.of("出门前确认实时天气和交通", "重要安排之间预留机动时间"));
        data.put("requires_live_route", true);
        return new ToolExecutionResult("builtin_itinerary_planner",
                "已生成" + city + "的基础行程，共 " + steps.size() + " 个时间段；实时路线需另行查询。", data, false);
    }

    private Map<String, Object> step(String time, String title, String location, String status) {
        return Map.of("time", time, "title", title, "location", location, "status", status);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
