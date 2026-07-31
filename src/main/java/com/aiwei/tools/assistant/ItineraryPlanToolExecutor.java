/* 2026-07-31 Codex 修改：多日游使用真实高德景点结果和返回图片生成分日行程。 */
package com.aiwei.tools.assistant;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import com.aiwei.tools.map.AmapClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成不依赖地图和用户会话的基础行程计划。
 */
@Component
public class ItineraryPlanToolExecutor implements ToolExecutor {

    private final AmapClient amapClient;

    public ItineraryPlanToolExecutor(AmapClient amapClient) {
        this.amapClient = amapClient;
    }

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
        int days = integer(args.get("days"), inferDays(args));
        days = Math.max(1, Math.min(7, days));
        boolean business = "business".equalsIgnoreCase(theme) || "商务".equals(theme);
        List<Map<String, Object>> places = business ? List.of() : loadAttractions(city, days * 3);
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Map<String, Object>> dayPlans = new ArrayList<>();
        for (int day = 1; day <= days; day++) {
            List<Map<String, Object>> daySteps = business
                    ? businessSteps(city)
                    : tourSteps(city, places, day);
            steps.addAll(daySteps);
            dayPlans.add(Map.of("day", day, "title", "第 " + day + " 天", "steps", daySteps));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "itinerary_plan");
        data.put("city", city);
        data.put("date", date);
        data.put("theme", theme);
        data.put("days_count", days);
        data.put("days", dayPlans);
        data.put("steps", steps);
        data.put("reminders", List.of("出门前确认实时天气和交通", "重要安排之间预留机动时间"));
        data.put("requires_live_route", true);
        return new ToolExecutionResult("builtin_itinerary_planner",
                "已生成" + city + days + "日行程，共 " + steps.size() + " 个安排；出发前请刷新实时路线。", data, false);
    }

    private List<Map<String, Object>> loadAttractions(String city, int limit) {
        try {
            return amapClient.nearby("景点", city, city, Math.min(10, limit), "gcj02");
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private List<Map<String, Object>> tourSteps(String city, List<Map<String, Object>> places, int day) {
        String[] times = {"09:00", "13:30", "18:00"};
        List<Map<String, Object>> result = new ArrayList<>();
        for (int slot = 0; slot < times.length; slot++) {
            int index = (day - 1) * times.length + slot;
            Map<String, Object> place = index < places.size() ? places.get(index) : Map.of();
            String title = text(place.get("name"));
            if (title.isBlank()) {
                title = slot == 0 ? "城市代表景点" : slot == 1 ? "城市街区漫游" : "本地美食与夜景";
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("day", day);
            step.put("time", times[slot]);
            step.put("title", title);
            step.put("location", title.equals("城市代表景点") ? city : text(place.getOrDefault("address", city)));
            step.put("status", slot == 0 ? "start" : "activity");
            step.put("detail", slot == 2 ? "放慢节奏，留出返程和休息时间。" : "建议停留约 2 小时，景点之间预留交通时间。");
            copy(place, step, "image_url");
            copy(place, step, "photos");
            copy(place, step, "rating");
            result.add(step);
        }
        return result;
    }

    private List<Map<String, Object>> businessSteps(String city) {
        return List.of(
                step("09:00", "确认当天商务目标", city, "focus"),
                step("11:30", "核对路线、天气和随身物品", city, "prepare"),
                step("14:00", "客户或方案沟通", city, "activity"),
                step("17:30", "返程并记录当天结果", city, "review"));
    }

    private Map<String, Object> step(String time, String title, String location, String status) {
        return Map.of("time", time, "title", title, "location", location, "status", status);
    }

    private void copy(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) target.put(key, value);
    }

    private int inferDays(Map<String, Object> args) {
        String source = text(args.get("theme")) + text(args.get("date"));
        if (source.contains("两日") || source.contains("2日")) return 2;
        if (source.contains("三日") || source.contains("3日")) return 3;
        return 1;
    }

    private int integer(Object value, int fallback) {
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception ignored) { return fallback; }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
