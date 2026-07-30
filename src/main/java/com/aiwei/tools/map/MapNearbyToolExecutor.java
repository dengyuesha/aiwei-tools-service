package com.aiwei.tools.map;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 高德周边地点搜索执行器。
 */
@Component
public class MapNearbyToolExecutor implements ToolExecutor {

    private final AmapClient client;

    /**
     * 创建周边搜索执行器。
     *
     * @param client 高德客户端
     */
    public MapNearbyToolExecutor(AmapClient client) {
        this.client = client;
    }

    @Override
    public String toolName() {
        return "map.nearby";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        Map<String, Object> args = request.arguments();
        String city = value(args, "city", request.context().city());
        String location = value(args, "location", contextLocation(request, city));
        String keyword = value(args, "keyword", "美食");
        int limit = integer(args.get("limit"), 5);
        List<Map<String, Object>> items = client.nearby(
                keyword, location, city, limit,
                value(args, "coordinate_system", request.context().coordinateSystem()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("keyword", keyword);
        data.put("location", location);
        data.put("city", city);
        data.put("items", items);
        String names = items.stream().limit(3)
                .map(item -> String.valueOf(item.get("name")))
                .reduce((a, b) -> a + "、" + b).orElse("");
        return new ToolExecutionResult("amap",
                location + "附近找到" + items.size() + "个" + keyword + "地点：" + names + "。",
                data, false);
    }

    private String contextLocation(ToolInvokeRequest request, String city) {
        if (request.context().longitude() != null && request.context().latitude() != null) {
            return request.context().longitude() + "," + request.context().latitude();
        }
        return city;
    }

    private String value(Map<String, Object> args, String key, String fallback) {
        Object raw = args.get(key);
        return raw == null || String.valueOf(raw).isBlank()
                ? fallback == null ? "" : fallback.trim()
                : String.valueOf(raw).trim();
    }

    private int integer(Object value, int fallback) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
