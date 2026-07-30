package com.aiwei.tools.rail;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 使用聚合数据查询真实火车票。
 */
@Component
public class RailSearchToolExecutor implements ToolExecutor {

    private final RailProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    /**
     * 创建火车票执行器。
     *
     * @param properties 火车票配置
     * @param webClientBuilder Spring WebClient 构建器
     * @param objectMapper JSON 解析器
     */
    public RailSearchToolExecutor(
            RailProperties properties,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * 返回稳定逻辑工具名。
     *
     * @return rail.search
     */
    @Override
    public String toolName() {
        return "rail.search";
    }

    /**
     * 查询并标准化真实车次。
     *
     * @param request 标准工具请求
     * @return 火车票结果
     * @throws IllegalArgumentException 路线、日期或筛选参数无效时抛出
     * @throws ToolExecutionException 数据源未配置或调用失败时抛出
     */
    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        if (properties.apiKey().isBlank()) {
            throw new ToolExecutionException(
                    "PROVIDER_NOT_CONFIGURED",
                    "JUHE_TRAIN_KEY is required",
                    false,
                    "火车票服务还没有配置好。");
        }
        Map<String, Object> arguments = request.arguments();
        String from = normalizeStation(firstNonBlank(arguments, "fromStation", "from"));
        String to = normalizeStation(firstNonBlank(arguments, "toStation", "to"));
        if (from.isBlank() || to.isBlank()) {
            throw new IllegalArgumentException("fromStation and toStation are required");
        }
        if (from.equals(to)) {
            throw new IllegalArgumentException("departure and arrival stations must be different");
        }
        LocalDate date = resolveTravelDate(firstNonBlank(arguments, "date", "travelDate"));
        validateTravelDate(date);
        String filter = normalizeFilter(firstNonBlank(arguments, "trainFilterFlags", "filter"));
        int limit = resolveLimit(arguments.get("limit"));
        URI uri = buildUri(from, to, date, filter);
        JsonNode response = fetch(uri);
        if (response.path("error_code").asInt(-1) != 0) {
            throw new ToolExecutionException(
                    "UPSTREAM_REJECTED",
                    "Juhe train API rejected request: " + response.path("reason").asText("unknown"),
                    false,
                    "火车票服务没有接受这次查询，请检查车站和日期。");
        }
        List<Map<String, Object>> trains = new ArrayList<>();
        JsonNode result = response.path("result");
        if (result.isArray()) {
            for (JsonNode ticket : result) {
                if (trains.size() >= limit) {
                    break;
                }
                trains.add(RailTicketNormalizer.normalize(ticket));
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", from);
        data.put("to", to);
        data.put("date", date.toString());
        data.put("filter", filter);
        data.put("ticket_kind", ticketKind(filter));
        data.put("trains", trains);
        return new ToolExecutionResult(
                "juhe_train_query",
                buildSummary(from, to, trains, filter),
                data,
                false);
    }

    private JsonNode fetch(URI uri) {
        try {
            String body = webClient.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(properties.timeoutMs()))
                    .block();
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (ToolExecutionException error) {
            throw error;
        } catch (Exception error) {
            throw new ToolExecutionException(
                    "UPSTREAM_FAILED",
                    "Juhe train API call failed: " + safeMessage(error),
                    true,
                    "火车票暂时查不到，请稍后再试。");
        }
    }

    private URI buildUri(String from, String to, LocalDate date, String filter) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(properties.endpoint())
                .queryParam("key", properties.apiKey())
                .queryParam("search_type", "1")
                .queryParam("departure_station", from)
                .queryParam("arrival_station", to)
                .queryParam("date", date)
                .queryParam("enable_booking", "2");
        if (!filter.isBlank()) {
            builder.queryParam("filter", filter);
        }
        return builder.build().encode().toUri();
    }

    private LocalDate resolveTravelDate(String rawDate) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        String value = rawDate == null ? "" : rawDate.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "", "tomorrow", "明天" -> today.plusDays(1);
            case "today", "今天" -> today;
            case "day_after_tomorrow", "后天" -> today.plusDays(2);
            default -> {
                try {
                    yield LocalDate.parse(value);
                } catch (DateTimeParseException error) {
                    throw new IllegalArgumentException("date must be today, tomorrow, 后天 or yyyy-MM-dd");
                }
            }
        };
    }

    private void validateTravelDate(LocalDate date) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if (date.isBefore(today)) {
            throw new IllegalArgumentException("travel date is in the past");
        }
        if (date.isAfter(today.plusDays(properties.maxDaysAhead()))) {
            throw new IllegalArgumentException(
                    "rail provider only supports dates within " + properties.maxDaysAhead() + " days");
        }
    }

    private String normalizeStation(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim()
                .replaceAll("^(查|查询|查一下|帮我查|帮我|从|到|去)+", "")
                .replaceAll("(今天|明天|后天)", "")
                .replaceAll("(市|省|特别行政区|自治区)$", "")
                .replaceAll("(火车票|高铁票|动车票|车票)$", "")
                .trim();
    }

    private String normalizeFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String filter = raw.trim().toUpperCase(Locale.ROOT);
        if (!filter.matches("[GDCZTK]{1,8}")) {
            throw new IllegalArgumentException("unsupported train filter: " + raw);
        }
        return filter;
    }

    private int resolveLimit(Object value) {
        if (value == null) {
            return 8;
        }
        try {
            return Math.max(1, Math.min(12, Integer.parseInt(String.valueOf(value))));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("limit must be an integer");
        }
    }

    private String buildSummary(
            String from,
            String to,
            List<Map<String, Object>> trains,
            String filter) {
        if (trains.isEmpty()) {
            return "没有查询到" + from + "到" + to + "的可用车次。";
        }
        StringBuilder summary = new StringBuilder()
                .append("查到")
                .append(trains.size())
                .append("趟")
                .append(ticketKind(filter).replace("票", ""))
                .append("。");
        int spoken = Math.min(3, trains.size());
        BigDecimal cheapest = null;
        for (int index = 0; index < spoken; index++) {
            Map<String, Object> train = trains.get(index);
            summary.append(index + 1)
                    .append("、")
                    .append(train.getOrDefault("train_no", "--"))
                    .append("，")
                    .append(train.getOrDefault("depart", "--"))
                    .append("出发，")
                    .append(train.getOrDefault("arrive", "--"))
                    .append("到达。");
            BigDecimal price = RailTicketNormalizer.cheapestPrice(train);
            if (price != null && (cheapest == null || price.compareTo(cheapest) < 0)) {
                cheapest = price;
            }
        }
        if (cheapest != null) {
            summary.append("目前看到的最低票价约")
                    .append(cheapest.stripTrailingZeros().toPlainString())
                    .append("元。");
        }
        return summary.toString();
    }

    private String ticketKind(String filter) {
        return switch (filter) {
            case "G" -> "高铁票";
            case "D" -> "动车票";
            case "Z" -> "直达票";
            case "T" -> "特快票";
            case "KTZ" -> "普速票";
            default -> "火车票";
        };
    }

    private String firstNonBlank(Map<String, Object> arguments, String... keys) {
        for (String key : keys) {
            Object value = arguments.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
