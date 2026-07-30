package com.aiwei.tools.map;

import com.aiwei.tools.contract.ToolContext;
import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 使用调用方显式设备上下文返回当前位置的执行器。
 */
@Component
public class LocationNowToolExecutor implements ToolExecutor {

    private final AmapClient client;

    /**
     * 创建当前位置执行器。
     *
     * @param client 高德客户端
     */
    public LocationNowToolExecutor(AmapClient client) {
        this.client = client;
    }

    @Override
    public String toolName() {
        return "location.now";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        ToolContext context = request.context();
        Double longitude = number(request.arguments().get("longitude"), context.longitude());
        Double latitude = number(request.arguments().get("latitude"), context.latitude());
        String coordinateSystem = String.valueOf(request.arguments().getOrDefault(
                "coordinate_system", context.coordinateSystem() == null ? "" : context.coordinateSystem()));
        if (longitude != null && latitude != null) {
            String coordinate = longitude + "," + latitude;
            Map<String, Object> data = new LinkedHashMap<>(
                    client.reverseGeocode(coordinate, coordinateSystem));
            data.put("latitude", latitude);
            data.put("longitude", longitude);
            String address = String.valueOf(data.getOrDefault("address", coordinate));
            return new ToolExecutionResult("amap_reverse_geocode",
                    "你当前大约位于" + address + "。", data, false);
        }
        String city = context.city() == null ? "" : context.city().trim();
        String district = context.district() == null ? "" : context.district().trim();
        if (city.isBlank()) {
            throw new ToolExecutionException("LOCATION_CONTEXT_REQUIRED",
                    "No coordinates or city in caller context", false,
                    "当前没有可用的设备定位信息。");
        }
        Map<String, Object> data = Map.of(
                "city", city,
                "district", district,
                "precision", "city");
        return new ToolExecutionResult("caller_context",
                "当前只能按" + city + district + "进行城市级定位。", data, false);
    }

    private Double number(Object value, Double fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException error) {
            throw new ToolExecutionException("INVALID_ARGUMENT",
                    "longitude/latitude must be numeric", false,
                    "经纬度格式不正确。");
        }
    }
}
