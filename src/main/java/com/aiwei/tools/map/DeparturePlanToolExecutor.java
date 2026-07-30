package com.aiwei.tools.map;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组合位置、路线、天气和场景预留时间的出发建议执行器。
 */
@Component
public class DeparturePlanToolExecutor implements ToolExecutor {

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private final AmapClient client;

    /**
     * 创建出发建议执行器。
     *
     * @param client 高德客户端
     */
    public DeparturePlanToolExecutor(AmapClient client) {
        this.client = client;
    }

    @Override
    public String toolName() {
        return "travel.departure.plan";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        Map<String, Object> args = request.arguments();
        String destination = value(args, "destination", value(args, "to", ""));
        long arrivalEpoch = longValue(args.get("arrival_time"));
        if (destination.isBlank() || arrivalEpoch <= 0) {
            throw new ToolExecutionException("INVALID_ARGUMENT",
                    "destination and arrival_time are required", false,
                    "请提供目的地和希望到达的时间。");
        }
        ZonedDateTime arrival = Instant.ofEpochSecond(arrivalEpoch).atZone(CHINA_ZONE);
        if (!arrival.isAfter(ZonedDateTime.now(CHINA_ZONE).plusMinutes(2))) {
            throw new ToolExecutionException("ARRIVAL_TIME_PASSED",
                    "arrival_time is in the past", false,
                    "希望到达的时间已经过去，请提供新的时间。");
        }
        String city = value(args, "city", request.context().city());
        String origin = value(args, "from", contextOrigin(request, city));
        String mode = value(args, "mode", "driving");
        int sceneBuffer = sceneBuffer(value(args, "scene", "general"));
        int weatherBuffer = 0;
        Map<String, Object> weather = Map.of();
        try {
            weather = client.weather(city);
            String condition = String.valueOf(weather.getOrDefault("condition", ""));
            if (condition.matches(".*(雨|雪|雷|雾|霾|沙尘|台风|暴风).*")) {
                weatherBuffer = 10;
            }
        } catch (ToolExecutionException ignored) {
            // 天气属于增强信息，失败时仍可依据真实路线给出建议。
        }
        int provisionalMinutes = 45;
        long provisionalDeparture = arrivalEpoch
                - (provisionalMinutes + sceneBuffer + weatherBuffer) * 60L;
        long now = Instant.now().getEpochSecond();
        boolean useForecast = "driving".equalsIgnoreCase(mode)
                && provisionalDeparture > now + 120
                && provisionalDeparture < now + java.time.Duration.ofDays(7).toSeconds();
        Map<String, Object> route = useForecast
                ? client.futureDrivingRoute(
                        origin, destination, city,
                        value(args, "coordinate_system", request.context().coordinateSystem()),
                        provisionalDeparture)
                : client.route(
                        origin, destination, city, mode, value(args, "preference", ""),
                        value(args, "coordinate_system", request.context().coordinateSystem()));
        int travelMinutes = ((Number) route.get("duration_minutes")).intValue();
        int totalBuffer = sceneBuffer + weatherBuffer;
        long recommended = arrivalEpoch - (travelMinutes + totalBuffer) * 60L;
        boolean leaveNow = recommended <= Instant.now().plusSeconds(120).getEpochSecond();
        if (leaveNow) {
            recommended = Instant.now().getEpochSecond();
        }

        List<Map<String, Object>> buffers = new ArrayList<>();
        buffers.add(Map.of("label", sceneLabel(value(args, "scene", "general")), "minutes", sceneBuffer));
        if (weatherBuffer > 0) {
            buffers.add(Map.of("label", "恶劣天气机动", "minutes", weatherBuffer));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("destination", destination);
        data.put("arrival_time", arrivalEpoch);
        data.put("arrival_label", arrival.format(DateTimeFormatter.ofPattern("MM月dd日 HH:mm")));
        data.put("recommended_departure_epoch", recommended);
        data.put("recommended_departure", leaveNow ? "现在出发"
                : Instant.ofEpochSecond(recommended).atZone(CHINA_ZONE)
                .format(DateTimeFormatter.ofPattern("MM月dd日 HH:mm")));
        data.put("route_duration_minutes", travelMinutes);
        data.put("distance", route.get("distance"));
        data.put("mode", route.get("mode"));
        data.put("buffer_minutes", totalBuffer);
        data.put("buffer_breakdown", buffers);
        data.put("weather", weather);
        data.put("route", route);
        data.put("forecast", useForecast);
        String departure = leaveNow ? "现在出发" : Instant.ofEpochSecond(recommended)
                .atZone(CHINA_ZONE).format(DateTimeFormatter.ofPattern("HH:mm")) + "左右出发";
        String summary = "建议你" + departure + "，预计路上" + travelMinutes
                + "分钟，另外预留" + totalBuffer + "分钟。"
                + (useForecast ? "已结合未来路况预测，出发前建议刷新。" : "出发前建议刷新实时路线。");
        return new ToolExecutionResult("amap_context_planner", summary, data, false);
    }

    private String contextOrigin(ToolInvokeRequest request, String city) {
        if (request.context().longitude() != null && request.context().latitude() != null) {
            return request.context().longitude() + "," + request.context().latitude();
        }
        String district = request.context().district() == null ? "" : request.context().district();
        return (city == null ? "" : city) + district;
    }

    private int sceneBuffer(String scene) {
        return switch (scene) {
            case "airport" -> 90;
            case "railway" -> 45;
            case "hospital", "event" -> 20;
            case "appointment" -> 15;
            case "school" -> 10;
            default -> 10;
        };
    }

    private String sceneLabel(String scene) {
        return switch (scene) {
            case "airport" -> "值机安检预留";
            case "railway" -> "进站候车预留";
            case "hospital" -> "挂号候诊预留";
            case "event" -> "入场取票预留";
            case "appointment" -> "准时到场预留";
            case "school" -> "接送机动预留";
            default -> "通用机动预留";
        };
    }

    private String value(Map<String, Object> args, String key, String fallback) {
        Object raw = args.get(key);
        return raw == null || String.valueOf(raw).isBlank()
                ? fallback == null ? "" : fallback.trim()
                : String.valueOf(raw).trim();
    }

    private long longValue(Object value) {
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }
}
