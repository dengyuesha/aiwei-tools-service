package com.aiwei.tools.map;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 高德道路和周边实时路况执行器。
 */
@Component
public class MapTrafficToolExecutor implements ToolExecutor {

    private final AmapClient client;

    /**
     * 创建路况执行器。
     *
     * @param client 高德客户端
     */
    public MapTrafficToolExecutor(AmapClient client) {
        this.client = client;
    }

    @Override
    public String toolName() {
        return "map.traffic";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        Map<String, Object> args = request.arguments();
        String city = value(args, "city", request.context().city());
        String road = value(args, "road", "");
        String location = value(args, "location", contextLocation(request, city));
        Map<String, Object> data = client.traffic(
                city, road, location, integer(args.get("radius"), 1000),
                value(args, "coordinate_system", request.context().coordinateSystem()));
        String target = String.valueOf(data.get("target"));
        return new ToolExecutionResult("amap",
                target + "当前" + data.get("label") + "。" + data.get("description"),
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
