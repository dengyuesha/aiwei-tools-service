package com.aiwei.tools.flight;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 多供应商真实航班查询执行器。
 */
@Component
public class FlightSearchToolExecutor implements ToolExecutor {

    private static final DateTimeFormatter TOP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Map<String, String> STATUS_LABELS = Map.ofEntries(
            Map.entry("PLAN", "计划"), Map.entry("DELAY", "延误"),
            Map.entry("CANCEL", "取消"), Map.entry("DEPART", "已起飞"),
            Map.entry("ARRIVE", "已到达"), Map.entry("RETURN", "返航"),
            Map.entry("ALTERNATE", "备降"));

    private final FlightProperties properties;
    private final FlightLocationResolver locationResolver;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    /**
     * 创建航班执行器。
     *
     * @param properties 航班供应商配置
     * @param locationResolver 城市和机场代码解析器
     * @param objectMapper JSON 解析器
     * @param webClientBuilder HTTP 客户端构建器
     */
    public FlightSearchToolExecutor(
            FlightProperties properties,
            FlightLocationResolver locationResolver,
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.locationResolver = locationResolver;
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder.build();
    }

    /**
     * 返回稳定逻辑工具名。
     *
     * @return flight.search
     */
    @Override
    public String toolName() {
        return "flight.search";
    }

    /**
     * 按配置顺序查询真实航班，并在 auto 模式自动降级。
     *
     * @param request 标准工具请求
     * @return UI 无关的航班列表
     */
    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        Map<String, Object> args = request.arguments();
        String from = normalizeLocation(first(args, "from", "fromCity", "departure"));
        String to = normalizeLocation(first(args, "to", "toCity", "arrival"));
        String flightNo = first(args, "flightNo", "flight_no").toUpperCase(Locale.ROOT);
        if (flightNo.isBlank() && (from.isBlank() || to.isBlank())) {
            throw new IllegalArgumentException("from/to or flightNo is required");
        }
        LocalDate date = resolveDate(first(args, "date", "travelDate"));
        int limit = limit(args.get("limit"));
        boolean needPrice = truthy(args.get("need_price")) || truthy(args.get("prefer_price"));

        List<String> providers = providerOrder(needPrice);
        if (providers.isEmpty()) {
            throw new ToolExecutionException(
                    "PROVIDER_NOT_CONFIGURED",
                    "No flight provider is configured",
                    false,
                    "航班服务还没有配置好。");
        }
        List<String> failures = new ArrayList<>();
        for (String provider : providers) {
            try {
                FlightResult result = switch (provider) {
                    case "variflight" -> queryVariflight(from, to, flightNo, date, limit);
                    case "alitrip" -> queryAlitrip(from, to, flightNo, date, limit);
                    case "juhe" -> queryJuhe(from, to, flightNo, date, limit);
                    default -> throw new IllegalStateException("unsupported provider: " + provider);
                };
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("from", result.from());
                data.put("to", result.to());
                data.put("date", date.toString());
                data.put("flights", result.items());
                return new ToolExecutionResult(
                        result.provider(),
                        summary(result.from(), result.to(), result.items()),
                        data,
                        false);
            } catch (Exception error) {
                failures.add(provider + ": " + safeMessage(error));
                if (!"auto".equals(properties.provider())) {
                    break;
                }
            }
        }
        throw new ToolExecutionException(
                "UPSTREAM_FAILED",
                String.join("; ", failures),
                true,
                "航班暂时查不到，稍后再试；你也可以先查火车票。");
    }

    private FlightResult queryVariflight(
            String from,
            String to,
            String flightNo,
            LocalDate date,
            int limit) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        String endpoint;
        if (!flightNo.isBlank()) {
            endpoint = "flight";
            params.put("fnum", flightNo);
        } else {
            endpoint = "flights";
            params.put("depcity", locationResolver.cityCode(from));
            params.put("arrcity", locationResolver.cityCode(to));
        }
        params.put("date", date.toString());
        JsonNode root = postJson(
                URI.create(properties.variflightUrl()),
                Map.of("endpoint", endpoint, "params", params),
                Map.of("X-VARIFLIGHT-KEY", properties.variflightApiKey()));
        if (root.path("code").asInt(-1) != 200) {
            throw new IllegalStateException(root.path("message").asText("variflight rejected request"));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        JsonNode data = root.path("data");
        if (data.isArray()) {
            for (JsonNode item : data) {
                if (items.size() >= limit) {
                    break;
                }
                items.add(normalizeVariflight(item));
            }
        }
        requireItems(items);
        return new FlightResult("variflight_mcp", from, to, items);
    }

    private FlightResult queryAlitrip(
            String from,
            String to,
            String flightNo,
            LocalDate date,
            int limit) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("flight_date", date.toString());
        payload.put("page_index", 1);
        payload.put("page_size", limit);
        payload.put("search_type", 3L);
        payload.put("is_syn", true);
        if (!flightNo.isBlank()) {
            payload.put("flight_no", flightNo);
        }
        if (!from.isBlank()) {
            payload.put("dep_city_code", locationResolver.cityCode(from));
            payload.put("dep_airport_code", locationResolver.airportCode(from));
        }
        if (!to.isBlank()) {
            payload.put("arr_city_code", locationResolver.cityCode(to));
            payload.put("arr_airport_code", locationResolver.airportCode(to));
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("method", "alitrip.flight.dynamic.query");
        params.put("app_key", properties.alitripAppKey());
        params.put("sign_method", properties.alitripSignMethod());
        params.put("timestamp", ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).format(TOP_TIMESTAMP));
        params.put("v", "2.0");
        params.put("format", "json");
        params.put("rq", objectMapper.writeValueAsString(payload));
        params.put("sign", AlitripSigner.sign(
                params,
                properties.alitripAppSecret(),
                properties.alitripSignMethod()));
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);
        String body = webClient.post()
                .uri(properties.alitripGateway())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(properties.timeoutMs()))
                .block();
        JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
        if (root.has("error_response")) {
            throw new IllegalStateException(root.path("error_response").path("msg").asText("alitrip rejected request"));
        }
        JsonNode result = root.path("alitrip_flight_dynamic_query_response").path("result");
        if (!result.path("success").asBoolean(false)) {
            throw new IllegalStateException(result.path("message").asText("alitrip rejected request"));
        }
        JsonNode models = result.path("models").path("flight_dynamic_model");
        List<Map<String, Object>> items = new ArrayList<>();
        if (models.isArray()) {
            for (JsonNode model : models) {
                if (items.size() >= limit) {
                    break;
                }
                items.add(normalizeAlitrip(model));
            }
        } else if (models.isObject()) {
            items.add(normalizeAlitrip(models));
        }
        requireItems(items);
        return new FlightResult("alitrip_flight_dynamic", from, to, items);
    }

    private FlightResult queryJuhe(
            String from,
            String to,
            String flightNo,
            LocalDate date,
            int limit) throws Exception {
        URI uri = UriComponentsBuilder.fromUriString(properties.juheUrl())
                .queryParam("key", properties.juheApiKey())
                .queryParam("departure", locationResolver.airportCode(from))
                .queryParam("arrival", locationResolver.airportCode(to))
                .queryParam("departureDate", date)
                .queryParam("maxSegments", 1)
                .queryParamIfPresent("flightNo",
                        flightNo.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(flightNo))
                .build()
                .encode()
                .toUri();
        JsonNode root = getJson(uri);
        if (root.has("error_code") && root.path("error_code").asInt() != 0) {
            throw new IllegalStateException(root.path("reason").asText("juhe rejected request"));
        }
        if (root.has("code") && !List.of("0", "200").contains(root.path("code").asText())) {
            throw new IllegalStateException(root.path("msg").asText("juhe rejected request"));
        }
        JsonNode flightInfo = root.path("result").path("flightInfo");
        List<Map<String, Object>> items = new ArrayList<>();
        if (flightInfo.isArray()) {
            for (JsonNode item : flightInfo) {
                if (items.size() >= limit) {
                    break;
                }
                items.add(normalizeJuhe(item));
            }
        } else if (flightInfo.isObject()) {
            items.add(normalizeJuhe(flightInfo));
        }
        requireItems(items);
        return new FlightResult("juhe_flight_query", from, to, items);
    }

    private Map<String, Object> normalizeVariflight(JsonNode item) {
        String depart = clock(text(item, "FlightDeptimePlanDate", "VeryZhunReadyDeptimeDate", "FlightDeptimeDate"));
        String arrive = clock(text(item, "FlightArrtimePlanDate", "VeryZhunReadyArrtimeDate", "FlightArrtimeDate"));
        Map<String, Object> out = baseFlight(
                textOr(item, "--", "FlightNo", "flight"),
                depart,
                arrive,
                "--",
                text(item, "FlightState", "AssistFlightState"));
        out.put("aircraft", text(item, "generic", "ftype"));
        out.put("on_time_rate", text(item, "OntimeRate"));
        out.put("depart_airport", text(item, "FlightDepcode", "FlightDepAirport"));
        out.put("arrive_airport", text(item, "FlightArrcode", "FlightArrAirport"));
        return out;
    }

    private Map<String, Object> normalizeAlitrip(JsonNode item) {
        String statusCode = text(item, "flight_status", "status");
        Map<String, Object> out = baseFlight(
                textOr(item, "--", "flight_no", "flight"),
                clock(text(item, "gmt_ready_depart", "gmt_plan_depart", "gmt_depart", "depTime")),
                clock(text(item, "gmt_ready_arrive", "gmt_plan_arrive", "gmt_arrive", "arrTime")),
                "--",
                STATUS_LABELS.getOrDefault(statusCode, statusCode));
        out.put("aircraft", text(item, "plane_type", "aircraft_number"));
        out.put("depart_airport", text(item, "depart_code", "dep_airport_code"));
        out.put("arrive_airport", text(item, "arrive_code", "arr_airport_code"));
        return out;
    }

    private Map<String, Object> normalizeJuhe(JsonNode item) {
        String price = textOr(item, "--", "ticketPrice", "price", "lowestPrice", "lowest_price")
                .replaceAll("[^\\d.]", "");
        if (price.isBlank()) {
            price = "--";
        }
        Map<String, Object> out = baseFlight(
                textOr(item, "--", "flightNo", "flight", "flight_no", "flightNumber"),
                clock(text(item, "departureTime", "depTime", "departure_time", "planDepTime")),
                clock(text(item, "arrivalTime", "arrTime", "arrival_time", "planArrTime")),
                price,
                text(item, "status", "flightStatus", "statusText", "state"));
        out.put("airline", text(item, "airlineName", "airline"));
        out.put("cabin", text(item, "cabin", "seatClass", "class"));
        return out;
    }

    private Map<String, Object> baseFlight(
            String flightNo,
            String depart,
            String arrive,
            String price,
            String status) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flight_no", flightNo);
        out.put("depart_time", depart);
        out.put("arrive_time", arrive);
        out.put("price", price);
        out.put("status", status);
        return out;
    }

    private List<String> providerOrder(boolean needPrice) {
        String configured = properties.provider();
        if (!"auto".equals(configured)) {
            return providerConfigured(configured) ? List.of(configured) : List.of();
        }
        List<String> order = new ArrayList<>();
        if (needPrice) {
            addIfConfigured(order, "alitrip");
            addIfConfigured(order, "juhe");
            addIfConfigured(order, "variflight");
        } else {
            addIfConfigured(order, "variflight");
            addIfConfigured(order, "alitrip");
            addIfConfigured(order, "juhe");
        }
        return order;
    }

    private void addIfConfigured(List<String> order, String provider) {
        if (providerConfigured(provider)) {
            order.add(provider);
        }
    }

    private boolean providerConfigured(String provider) {
        return switch (provider) {
            case "variflight" -> !properties.variflightApiKey().isBlank();
            case "alitrip" -> !properties.alitripAppKey().isBlank()
                    && !properties.alitripAppSecret().isBlank();
            case "juhe" -> !properties.juheApiKey().isBlank() && !properties.juheUrl().isBlank();
            default -> false;
        };
    }

    private JsonNode getJson(URI uri) throws Exception {
        String body = webClient.get().uri(uri).retrieve().bodyToMono(String.class)
                .timeout(Duration.ofMillis(properties.timeoutMs())).block();
        return objectMapper.readTree(body == null ? "{}" : body);
    }

    private JsonNode postJson(URI uri, Object body, Map<String, String> headers) throws Exception {
        WebClient.RequestBodySpec request = webClient.post().uri(uri).contentType(MediaType.APPLICATION_JSON);
        headers.forEach(request::header);
        String response = request.bodyValue(body).retrieve().bodyToMono(String.class)
                .timeout(Duration.ofMillis(Math.max(properties.timeoutMs(), 20000L))).block();
        return objectMapper.readTree(response == null ? "{}" : response);
    }

    private LocalDate resolveDate(String raw) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "", "tomorrow", "明天", "明日" -> today.plusDays(1);
            case "today", "今天", "今日" -> today;
            case "day_after_tomorrow", "后天" -> today.plusDays(2);
            case "大后天" -> today.plusDays(3);
            default -> {
                try {
                    yield LocalDate.parse(value);
                } catch (DateTimeParseException error) {
                    throw new IllegalArgumentException("date must be today, tomorrow, 后天 or yyyy-MM-dd");
                }
            }
        };
    }

    private String summary(String from, String to, List<Map<String, Object>> items) {
        StringBuilder text = new StringBuilder("查到").append(items.size()).append("个航班");
        if (!from.isBlank() && !to.isBlank()) {
            text.append("，").append(from).append("到").append(to);
        }
        text.append("。");
        for (int index = 0; index < Math.min(3, items.size()); index++) {
            Map<String, Object> item = items.get(index);
            text.append(index + 1).append("、")
                    .append(item.getOrDefault("flight_no", "--")).append("，")
                    .append(item.getOrDefault("depart_time", "--")).append("起飞，")
                    .append(item.getOrDefault("arrive_time", "--")).append("到达。");
        }
        return text.toString();
    }

    private void requireItems(List<Map<String, Object>> items) {
        items.removeIf(item -> "--".equals(item.get("flight_no"))
                && String.valueOf(item.getOrDefault("depart_time", "")).isBlank());
        if (items.isEmpty()) {
            throw new IllegalStateException("provider returned no displayable flights");
        }
    }

    private String text(JsonNode node, String... keys) {
        return textOr(node, "", keys);
    }

    private String textOr(JsonNode node, String fallback, String... keys) {
        for (String key : keys) {
            String value = node.path(key).asText("").trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    private String clock(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() >= 16 && value.charAt(10) == ' ') {
            return value.substring(11, 16);
        }
        if (value.length() >= 5 && value.charAt(2) == ':') {
            return value.substring(0, 5);
        }
        return value;
    }

    private String first(Map<String, Object> args, String... keys) {
        for (String key : keys) {
            Object value = args.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private String normalizeLocation(String value) {
        return value == null ? "" : value.trim()
                .replaceAll("^(从|由)", "")
                .replaceAll("(出发|起飞)$", "")
                .trim();
    }

    private int limit(Object value) {
        try {
            return Math.max(1, Math.min(20, Integer.parseInt(String.valueOf(value))));
        } catch (Exception ignored) {
            return 8;
        }
    }

    private boolean truthy(Object value) {
        return value != null && List.of("true", "1", "yes", "是")
                .contains(String.valueOf(value).trim().toLowerCase(Locale.ROOT));
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private record FlightResult(
            String provider,
            String from,
            String to,
            List<Map<String, Object>> items) {
    }
}
