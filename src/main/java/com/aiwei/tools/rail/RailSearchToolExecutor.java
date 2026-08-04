/*
 * 2026-07-31 Codex 修改：12306 降级结果补齐统一时间别名、席位余票和官方票价。
 * 2026-07-31 Codex 修改：聚合铁路接口限额或故障时降级到 12306 公开余票查询，避免整张生成式 UI 失败。
 */
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
import reactor.core.publisher.Mono;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用聚合数据查询真实火车票。
 */
@Component
public class RailSearchToolExecutor implements ToolExecutor {

    private static final String OFFICIAL_STATION_URL =
            "https://kyfw.12306.cn/otn/resources/js/framework/station_name.js";
    private static final String OFFICIAL_QUERY_URL =
            "https://kyfw.12306.cn/otn/leftTicket/query";
    private static final String OFFICIAL_PRICE_URL =
            "https://kyfw.12306.cn/otn/leftTicket/queryTicketPrice";
    private static final String OFFICIAL_BOOKING_URL = "https://www.12306.cn/index/";
    private static final Pattern OFFICIAL_STATION_PATTERN = Pattern.compile(
            "@[^|]*\\|([^|]+)\\|([A-Z]+)\\|");

    private final RailProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private volatile Map<String, String> officialStationCodes = Map.of();

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
        this.webClient = webClientBuilder
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
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
        JsonNode response;
        try {
            response = fetch(uri);
        } catch (ToolExecutionException primaryError) {
            return executeOfficialFallback(from, to, date, filter, limit, primaryError);
        }
        if (response.path("error_code").asInt(-1) != 0) {
            ToolExecutionException primaryError = new ToolExecutionException(
                    "UPSTREAM_REJECTED",
                    "Juhe train API rejected request: " + response.path("reason").asText("unknown"),
                    false,
                    "火车票聚合数据源没有接受这次查询。");
            return executeOfficialFallback(from, to, date, filter, limit, primaryError);
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
        data.put("booking_url", OFFICIAL_BOOKING_URL);
        data.put("trains", trains);
        return new ToolExecutionResult(
                "juhe_train_query",
                buildSummary(from, to, trains, filter),
                data,
                false);
    }

    /**
     * 使用 12306 公开余票查询作为只读降级数据源。
     *
     * @param from 出发站
     * @param to 到达站
     * @param date 出发日期
     * @param filter 车次类型过滤
     * @param limit 最大结果数
     * @param primaryError 主数据源失败原因
     * @return 标准铁路查询结果
     */
    private ToolExecutionResult executeOfficialFallback(
            String from,
            String to,
            LocalDate date,
            String filter,
            int limit,
            ToolExecutionException primaryError) {
        try {
            Map<String, String> stationCodes = officialStationCodes();
            String fromCode = stationCodes.getOrDefault(from, "");
            String toCode = stationCodes.getOrDefault(to, "");
            if (fromCode.isBlank() || toCode.isBlank()) {
                throw new IllegalArgumentException("12306 station code not found");
            }
            URI uri = UriComponentsBuilder.fromUriString(OFFICIAL_QUERY_URL)
                    .queryParam("leftTicketDTO.train_date", date)
                    .queryParam("leftTicketDTO.from_station", fromCode)
                    .queryParam("leftTicketDTO.to_station", toCode)
                    .queryParam("purpose_codes", "ADULT")
                    .build().encode().toUri();
            String cookie = officialCookie(from, fromCode, to, toCode, date);
            JsonNode root = fetchOfficialQuery(uri, cookie);
            String redirectedPath = root.path("c_url").asText("");
            if (!redirectedPath.isBlank()) {
                URI redirectedUri = UriComponentsBuilder
                        .fromUriString("https://kyfw.12306.cn/otn/" + redirectedPath)
                        .queryParam("leftTicketDTO.train_date", date)
                        .queryParam("leftTicketDTO.from_station", fromCode)
                        .queryParam("leftTicketDTO.to_station", toCode)
                        .queryParam("purpose_codes", "ADULT")
                        .build().encode().toUri();
                root = fetchOfficialQuery(redirectedUri, cookie);
            }
            if (root.path("httpstatus").asInt(0) != 200 || !root.path("data").path("result").isArray()) {
                throw new IllegalStateException("12306 response does not contain ticket results");
            }
            List<Map<String, Object>> trains = new ArrayList<>();
            for (JsonNode row : root.path("data").path("result")) {
                Map<String, Object> train = normalizeOfficialTrain(row.asText(""), from, to);
                String trainNo = String.valueOf(train.getOrDefault("train_no", ""));
                if (trainNo.isBlank() || !matchesFilter(trainNo, filter)) {
                    continue;
                }
                trains.add(train);
                if (trains.size() >= limit) {
                    break;
                }
            }
            enrichOfficialPrices(trains, date, cookie);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("from", from);
            data.put("to", to);
            data.put("date", date.toString());
            data.put("filter", filter);
            data.put("ticket_kind", ticketKind(filter));
            data.put("booking_url", OFFICIAL_BOOKING_URL);
            data.put("trains", trains);
            return new ToolExecutionResult(
                    "official_12306",
                    buildSummary(from, to, trains, filter),
                    data,
                    false);
        } catch (Exception fallbackError) {
            throw new ToolExecutionException(
                    primaryError.code(),
                    primaryError.getMessage() + "; 12306 fallback failed: " + safeMessage(fallbackError),
                    primaryError.retryable(),
                    "火车票暂时查不到，请稍后再试。");
        }
    }

    private Map<String, String> officialStationCodes() {
        Map<String, String> cached = officialStationCodes;
        if (!cached.isEmpty()) {
            return cached;
        }
        synchronized (this) {
            if (!officialStationCodes.isEmpty()) {
                return officialStationCodes;
            }
            String script = webClient.get()
                    .uri(OFFICIAL_STATION_URL)
                    .header("User-Agent", "Mozilla/5.0")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(properties.timeoutMs()))
                    .block();
            Map<String, String> parsed = parseOfficialStationCodes(script == null ? "" : script);
            if (parsed.isEmpty()) {
                throw new IllegalStateException("12306 station list is empty");
            }
            officialStationCodes = parsed;
            return parsed;
        }
    }

    private JsonNode fetchOfficialQuery(URI uri, String cookie) throws Exception {
        String body = webClient.get()
                .uri(uri)
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://kyfw.12306.cn/otn/leftTicket/init")
                .header("Cookie", cookie)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(properties.timeoutMs()))
                .block();
        return objectMapper.readTree(body == null ? "{}" : body);
    }

    /**
     * 并发查询入选车次的官方席位价格；价格接口失败时保留余票和时刻。
     *
     * @param trains 已筛选车次
     * @param date 出发日期
     * @param cookie 12306 查询 Cookie
     */
    private void enrichOfficialPrices(List<Map<String, Object>> trains, LocalDate date, String cookie) {
        List<Mono<Void>> requests = trains.stream().map(train -> {
            URI uri = UriComponentsBuilder.fromUriString(OFFICIAL_PRICE_URL)
                    .queryParam("train_no", train.getOrDefault("_official_train_no", ""))
                    .queryParam("from_station_no", train.getOrDefault("_from_station_no", ""))
                    .queryParam("to_station_no", train.getOrDefault("_to_station_no", ""))
                    .queryParam("seat_types", train.getOrDefault("_seat_types", ""))
                    .queryParam("train_date", date)
                    .build().encode().toUri();
            return webClient.get()
                    .uri(uri)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "https://kyfw.12306.cn/otn/leftTicket/init")
                    .header("Cookie", cookie)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(properties.timeoutMs()))
                    .doOnNext(body -> {
                        try {
                            applyOfficialPrices(train, objectMapper.readTree(body).path("data"));
                        } catch (Exception ignored) {
                            // 单趟车价格格式异常时只缺价格，余票和时刻仍可展示。
                        }
                    })
                    .onErrorResume(error -> Mono.empty())
                    .then();
        }).toList();
        try {
            Mono.when(requests)
                    .block(Duration.ofMillis(Math.max(1000, properties.timeoutMs() + 500L)));
        } catch (RuntimeException ignored) {
            // 官方价格接口偶发超时时不能覆盖已经成功返回的余票主查询。
        } finally {
            trains.forEach(train -> {
                train.remove("_official_train_no");
                train.remove("_from_station_no");
                train.remove("_to_station_no");
                train.remove("_seat_types");
            });
        }
    }

    static Map<String, String> parseOfficialStationCodes(String script) {
        Map<String, String> stations = new LinkedHashMap<>();
        Matcher matcher = OFFICIAL_STATION_PATTERN.matcher(script == null ? "" : script);
        while (matcher.find()) {
            stations.putIfAbsent(matcher.group(1), matcher.group(2));
        }
        return Map.copyOf(stations);
    }

    static Map<String, Object> normalizeOfficialTrain(String row, String from, String to) {
        String[] fields = (row == null ? "" : row).split("\\|", -1);
        Map<String, Object> train = new LinkedHashMap<>();
        if (fields.length < 33) {
            return train;
        }
        String trainNo = fields[3];
        train.put("train_no", trainNo);
        train.put("from_station", from);
        train.put("to_station", to);
        train.put("depart", fields[8]);
        train.put("arrive", fields[9]);
        train.put("departure_time", fields[8]);
        train.put("arrival_time", fields[9]);
        train.put("duration", fields[10]);
        train.put("train_type", trainTypeLabel(trainNo));
        train.put("_official_train_no", field(fields, 2));
        train.put("_from_station_no", field(fields, 16));
        train.put("_to_station_no", field(fields, 17));
        train.put("_seat_types", field(fields, 35));
        Map<String, String> seats = new LinkedHashMap<>();
        putSeat(seats, "商务座", field(fields, 32));
        putSeat(seats, "一等座", field(fields, 31));
        putSeat(seats, "二等座", field(fields, 30));
        putSeat(seats, "软卧", field(fields, 23));
        putSeat(seats, "硬卧", field(fields, 28));
        putSeat(seats, "硬座", field(fields, 29));
        putSeat(seats, "无座", field(fields, 26));
        train.put("seats", seats);
        return train;
    }

    /**
     * 把 12306 票价代码合并进已有席位余票文字。
     *
     * @param train 标准车次
     * @param prices 12306 data 节点
     */
    static void applyOfficialPrices(Map<String, Object> train, JsonNode prices) {
        Object rawSeats = train.get("seats");
        if (!(rawSeats instanceof Map<?, ?> rawMap) || prices == null || !prices.isObject()) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> seats = (Map<String, String>) rawMap;
        Map<String, String> codes = Map.of(
                "商务座", "A9", "一等座", "M", "二等座", "O",
                "高级软卧", "A6", "软卧", "A4", "硬卧", "A3",
                "硬座", "A1", "无座", "WZ");
        codes.forEach((seatName, code) -> {
            String price = prices.path(code).asText("").replace("¥", "").trim();
            if (!price.isBlank() && seats.containsKey(seatName)) {
                seats.put(seatName, seats.get(seatName) + " ¥" + price);
            }
        });
    }

    private static void putSeat(Map<String, String> seats, String name, String raw) {
        if (raw == null || raw.isBlank() || "无".equals(raw) || "--".equals(raw)) {
            return;
        }
        String value = "有".equals(raw) ? "有票" : "候补".equals(raw) ? raw : raw + "张";
        seats.put(name, value);
    }

    private static String field(String[] fields, int index) {
        return index >= 0 && index < fields.length ? fields[index] : "";
    }

    private static String trainTypeLabel(String trainNo) {
        if (trainNo == null || trainNo.isBlank()) {
            return "列车";
        }
        return switch (Character.toUpperCase(trainNo.charAt(0))) {
            case 'G' -> "高铁";
            case 'D' -> "动车";
            case 'C' -> "城际";
            case 'Z' -> "直达";
            case 'T' -> "特快";
            case 'K' -> "快速";
            default -> "普通";
        };
    }

    private boolean matchesFilter(String trainNo, String filter) {
        return filter.isBlank()
                || filter.indexOf(Character.toUpperCase(trainNo.charAt(0))) >= 0;
    }

    private String officialCookie(String from, String fromCode, String to, String toCode, LocalDate date) {
        return "_jc_save_fromStation=" + from + "%2C" + fromCode
                + "; _jc_save_toStation=" + to + "%2C" + toCode
                + "; _jc_save_fromDate=" + date
                + "; _jc_save_toDate=" + date
                + "; _jc_save_wfdc_flag=dc";
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
        }
        List<CheapestRailOffer> cheapestOffers = cheapestOffers(trains);
        if (!cheapestOffers.isEmpty()) {
            CheapestRailOffer first = cheapestOffers.get(0);
            summary.append("目前看到的最低票价约")
                    .append(first.price().stripTrailingZeros().toPlainString())
                    .append("元，对应")
                    .append(cheapestOffers.stream().limit(3)
                            .map(CheapestRailOffer::trainNo)
                            .distinct()
                            .reduce((left, right) -> left + "、" + right)
                            .orElse(first.trainNo()))
                    .append("的")
                    .append(first.seatName());
            if (cheapestOffers.stream().map(CheapestRailOffer::trainNo).distinct().count() > 3) {
                summary.append("等车次");
            }
            summary.append("。");
        }
        return summary.toString();
    }

    private List<CheapestRailOffer> cheapestOffers(List<Map<String, Object>> trains) {
        List<CheapestRailOffer> offers = new ArrayList<>();
        BigDecimal globalCheapest = null;
        for (Map<String, Object> train : trains) {
            Object seatsObject = train.get("seats");
            if (!(seatsObject instanceof Map<?, ?> seats)) {
                continue;
            }
            for (Map.Entry<?, ?> entry : seats.entrySet()) {
                String value = String.valueOf(entry.getValue());
                if (value.contains("无票")) {
                    continue;
                }
                int marker = value.indexOf('¥');
                if (marker < 0 || marker == value.length() - 1) {
                    continue;
                }
                try {
                    BigDecimal price = new BigDecimal(value.substring(marker + 1).trim());
                    CheapestRailOffer offer = new CheapestRailOffer(
                            String.valueOf(train.getOrDefault("train_no", "--")),
                            String.valueOf(entry.getKey()),
                            price);
                    if (globalCheapest == null || price.compareTo(globalCheapest) < 0) {
                        globalCheapest = price;
                        offers.clear();
                        offers.add(offer);
                    } else if (price.compareTo(globalCheapest) == 0) {
                        offers.add(offer);
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore upstream placeholders such as "--".
                }
            }
        }
        return offers;
    }

    private record CheapestRailOffer(String trainNo, String seatName, BigDecimal price) {
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
