/* 2026-07-31 Codex 修改：出行比较加入真实驾车路线，支持高铁、开车、飞机三种方式。 */
package com.aiwei.tools.travel;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import com.aiwei.tools.flight.FlightSearchToolExecutor;
import com.aiwei.tools.rail.RailSearchToolExecutor;
import com.aiwei.tools.map.AmapClient;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组合真实航班与火车票结果的出行对比执行器。
 */
@Component
public class TravelCompareToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(TravelCompareToolExecutor.class);
    private final FlightSearchToolExecutor flightExecutor;
    private final RailSearchToolExecutor railExecutor;
    private final AmapClient amapClient;

    /**
     * 创建出行对比执行器。
     *
     * @param flightExecutor 航班执行器
     * @param railExecutor 火车票执行器
     */
    public TravelCompareToolExecutor(
            FlightSearchToolExecutor flightExecutor,
            RailSearchToolExecutor railExecutor,
            AmapClient amapClient) {
        this.flightExecutor = flightExecutor;
        this.railExecutor = railExecutor;
        this.amapClient = amapClient;
    }

    @Override
    public String toolName() {
        return "travel.compare";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        String from = value(request.arguments(), "from", "fromStation");
        String to = value(request.arguments(), "to", "toStation");
        String date = value(request.arguments(), "date");
        if (from.isBlank() || to.isBlank()) {
            throw new ToolExecutionException("INVALID_ARGUMENT", "from/to are required",
                    false, "请提供出发地和目的地。");
        }
        Map<String, Object> flightArgs = new LinkedHashMap<>();
        flightArgs.put("from", from);
        flightArgs.put("to", to);
        flightArgs.put("date", date.isBlank() ? "tomorrow" : date);
        flightArgs.put("need_price", true);
        Map<String, Object> railArgs = new LinkedHashMap<>();
        railArgs.put("fromStation", from);
        railArgs.put("toStation", to);
        railArgs.put("date", date.isBlank() ? "tomorrow" : date);
        copyIfPresent(request.arguments(), railArgs, "trainFilterFlags");

        ToolExecutionResult flight = attempt(flightExecutor, withArguments(request, flightArgs));
        ToolExecutionResult rail = attempt(railExecutor, withArguments(request, railArgs));
        Map<String, Object> driving = attemptDriving(from, to, request);
        if (flight == null && rail == null && driving.isEmpty()) {
            throw new ToolExecutionException("UPSTREAM_FAILED",
                    "Both flight and rail providers failed", true,
                    "航班和火车票暂时都查不到，请稍后再试。");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", from);
        data.put("to", to);
        data.put("date", date.isBlank() ? "tomorrow" : date);
        if (flight != null) {
            data.put("flight", resultMap(flight));
            data.put("cheapest_flight", cheapest(
                    list(flight.data().get("flights")), "price", "flight_no"));
        }
        if (rail != null) {
            data.put("rail", resultMap(rail));
            data.put("cheapest_rail", cheapestRail(list(rail.data().get("trains"))));
        }
        if (!driving.isEmpty()) {
            data.put("driving", driving);
        }
        String summary = from + "到" + to + "的出行对比已完成。"
                + (flight == null ? "航班暂时不可用。" : flight.summary())
                + (rail == null ? "火车票暂时不可用。" : rail.summary())
                + (driving.isEmpty() ? "驾车路线暂时不可用。" : "驾车预计" + driving.get("duration") + "，全程" + driving.get("distance") + "。");
        return new ToolExecutionResult("travel_compare", summary, data, false);
    }

    private Map<String, Object> attemptDriving(String from, String to, ToolInvokeRequest request) {
        try {
            // from/to in this composite tool are normally cities in different regions.
            // Reusing the device city as a geocoding restriction can incorrectly resolve
            // both places into the same city (for example Shenzhen -> Guangzhou).
            Map<String, Object> route = new LinkedHashMap<>(amapClient.route(
                    from, to, "", "driving", "",
                    request.context().coordinateSystem()));
            route.putIfAbsent("duration", formatDuration(route.get("duration_minutes")));
            return route;
        } catch (RuntimeException error) {
            log.warn("Driving comparison failed for {} -> {}: {}", from, to, error.getMessage());
            return Map.of();
        }
    }

    private String formatDuration(Object rawMinutes) {
        try {
            long minutes = Long.parseLong(String.valueOf(rawMinutes));
            if (minutes < 60) {
                return minutes + "分钟";
            }
            long hours = minutes / 60;
            long remainder = minutes % 60;
            return remainder == 0 ? hours + "小时" : hours + "小时" + remainder + "分钟";
        } catch (Exception ignored) {
            return "--";
        }
    }

    private ToolExecutionResult attempt(ToolExecutor executor, ToolInvokeRequest request) {
        try {
            return executor.execute(request);
        } catch (RuntimeException ignored) {
            // 对比工具允许单个供应商失败，只有两边都失败才返回整体失败。
            return null;
        }
    }

    private ToolInvokeRequest withArguments(ToolInvokeRequest source, Map<String, Object> arguments) {
        return new ToolInvokeRequest(source.requestId(), source.tenantId(), source.userId(),
                source.sessionId(), arguments, source.context(), source.idempotencyKey());
    }

    private Map<String, Object> resultMap(ToolExecutionResult result) {
        return Map.of("provider", result.provider(), "summary", result.summary(), "data", result.data());
    }

    private Map<String, Object> cheapest(List<Map<String, Object>> items, String priceKey, String nameKey) {
        BigDecimal best = null;
        Map<String, Object> selected = Map.of();
        for (Map<String, Object> item : items) {
            BigDecimal price = decimal(item.get(priceKey));
            if (price != null && (best == null || price.compareTo(best) < 0)) {
                best = price;
                selected = Map.of("name", item.getOrDefault(nameKey, "--"), "price", price);
            }
        }
        return selected;
    }

    private Map<String, Object> cheapestRail(List<Map<String, Object>> items) {
        BigDecimal best = null;
        Map<String, Object> selected = Map.of();
        for (Map<String, Object> item : items) {
            Object seats = item.get("seats");
            if (seats instanceof Map<?, ?> seatMap) {
                for (Map.Entry<?, ?> entry : seatMap.entrySet()) {
                    BigDecimal price = decimal(entry.getValue());
                    if (price != null && (best == null || price.compareTo(best) < 0)) {
                        best = price;
                        selected = Map.of(
                                "name", item.getOrDefault("train_no", "--"),
                                "seat", String.valueOf(entry.getKey()),
                                "price", price);
                    }
                }
                continue;
            }
            if (!(seats instanceof List<?> seatList)) {
                continue;
            }
            for (Object raw : seatList) {
                if (!(raw instanceof Map<?, ?> seat)) {
                    continue;
                }
                BigDecimal price = decimal(seat.get("price"));
                if (price != null && (best == null || price.compareTo(best) < 0)) {
                    best = price;
                    selected = Map.of(
                            "name", item.getOrDefault("train_no", "--"),
                            "seat", String.valueOf(seat.containsKey("name") ? seat.get("name") : "--"),
                            "price", price);
                }
            }
        }
        return selected;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private BigDecimal decimal(Object value) {
        try {
            String normalized = String.valueOf(value).replaceAll("[^\\d.]", "");
            return normalized.isBlank() ? null : new BigDecimal(normalized);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String value(Map<String, Object> args, String... keys) {
        for (String key : keys) {
            Object value = args.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.get(key) != null && !String.valueOf(source.get(key)).isBlank()) {
            target.put(key, source.get(key));
        }
    }
}
