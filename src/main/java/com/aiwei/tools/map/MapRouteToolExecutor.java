package com.aiwei.tools.map;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 驾车、步行、骑行和公共交通路线执行器。
 */
@Component
public class MapRouteToolExecutor implements ToolExecutor {

    private final AmapClient client;

    /**
     * 创建路线执行器。
     *
     * @param client 高德客户端
     */
    public MapRouteToolExecutor(AmapClient client) {
        this.client = client;
    }

    @Override
    public String toolName() {
        return "map.route";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        Map<String, Object> args = request.arguments();
        String from = value(args, "from", contextOrigin(request));
        String to = value(args, "to", "");
        if (from.isBlank() || to.isBlank()) {
            throw new ToolExecutionException("INVALID_ARGUMENT", "from and to are required",
                    false, "请提供路线起点和终点。");
        }
        String city = value(args, "city", nullSafe(request.context().city()));
        Map<String, Object> route = client.route(
                from, to, city, value(args, "mode", "driving"),
                value(args, "preference", ""), value(args, "coordinate_system",
                        nullSafe(request.context().coordinateSystem())));
        String summary = "已规划从" + from + "到" + to + "的"
                + modeLabel(String.valueOf(route.get("mode"))) + "路线，全程"
                + route.get("distance") + "，预计" + route.get("duration_minutes") + "分钟。";
        return new ToolExecutionResult("amap", summary, route, false);
    }

    private String contextOrigin(ToolInvokeRequest request) {
        if (request.context().longitude() != null && request.context().latitude() != null) {
            return request.context().longitude() + "," + request.context().latitude();
        }
        return nullSafe(request.context().city()) + nullSafe(request.context().district());
    }

    private String value(Map<String, Object> args, String key, String fallback) {
        Object raw = args.get(key);
        return raw == null || String.valueOf(raw).isBlank() ? nullSafe(fallback) : String.valueOf(raw).trim();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value.trim();
    }

    private String modeLabel(String mode) {
        return switch (mode) {
            case "walking" -> "步行";
            case "cycling" -> "骑行";
            case "transit" -> "公共交通";
            default -> "驾车";
        };
    }
}
