package com.aiwei.tools.map;

import com.aiwei.tools.execution.ToolExecutionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/*
 * 2026-08-01 Codex 修改：跨城地点在设备城市限定失败时自动执行全国地理编码重试。
 * 2026-07-31 Codex 修改：路线结果补充可展示地址和真实路线折线，避免前端暴露原始经纬度。
 */
/**
 * 高德地图 Web Service 中立客户端。
 *
 * <p>该类只处理供应商协议和字段归一化，不生成 AINAS 或 aiweios-server 的卡片结构。</p>
 */
@Component
public class AmapClient {

    private static final Logger logger = LoggerFactory.getLogger(AmapClient.class);
    private final MapProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    /**
     * 创建高德客户端。
     *
     * @param properties 地图配置
     * @param objectMapper JSON 解析器
     * @param builder HTTP 客户端构建器
     */
    public AmapClient(MapProperties properties, ObjectMapper objectMapper, WebClient.Builder builder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = builder.build();
    }

    /**
     * 将地名解析成 GCJ-02 坐标。
     *
     * @param place 地名或经纬度
     * @param city 城市限定
     * @param coordinateSystem 输入坐标系
     * @return “经度,纬度”
     */
    public String resolvePoint(String place, String city, String coordinateSystem) {
        requireKey();
        String normalized = text(place);
        if (isCoordinate(normalized)) {
            return convertCoordinate(normalized, coordinateSystem);
        }
        if (normalized.isBlank()) {
            throw input("地点不能为空。");
        }
        JsonNode root;
        try {
            root = geocode(normalized, city);
        } catch (ToolExecutionException error) {
            // city 通常来自设备当前位置，不能让它限制跨城目的地。
            if (text(city).isBlank()) {
                throw error;
            }
            logger.info("AMap geocode with city restriction failed; retrying without city, place={}, city={}",
                    normalized, city);
            root = geocode(normalized, "");
        }
        JsonNode geocodes = root.path("geocodes");
        if ((!geocodes.isArray() || geocodes.isEmpty()) && !text(city).isBlank()) {
            root = geocode(normalized, "");
            geocodes = root.path("geocodes");
        }
        if (!geocodes.isArray() || geocodes.isEmpty()) {
            throw new ToolExecutionException("PLACE_NOT_FOUND", "AMap found no geocode for " + normalized,
                    false, "没有找到地点“" + normalized + "”。");
        }
        String location = geocodes.get(0).path("location").asText("").trim();
        if (!isCoordinate(location)) {
            throw upstream("高德没有返回有效地点坐标。");
        }
        return location;
    }

    private JsonNode geocode(String address, String city) {
        URI uri = uri("/v3/geocode/geo")
                .queryParam("key", properties.apiKey())
                .queryParam("address", address)
                .queryParamIfPresent("city", optional(city))
                .build().encode().toUri();
        return get(uri, "地理编码");
    }

    /**
     * 逆地理编码坐标。
     *
     * @param coordinate 经纬度
     * @param coordinateSystem 输入坐标系
     * @return 标准位置字段
     */
    public Map<String, Object> reverseGeocode(String coordinate, String coordinateSystem) {
        String converted = resolvePoint(coordinate, "", coordinateSystem);
        URI uri = uri("/v3/geocode/regeo")
                .queryParam("key", properties.apiKey())
                .queryParam("location", converted)
                .queryParam("radius", 1000)
                .queryParam("extensions", "base")
                .build().encode().toUri();
        JsonNode root = get(uri, "逆地理编码");
        JsonNode regeocode = root.path("regeocode");
        JsonNode component = regeocode.path("addressComponent");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("coordinate", converted);
        result.put("province", nodeText(component.path("province")));
        String city = nodeText(component.path("city"));
        if (city.isBlank()) {
            city = nodeText(component.path("province"));
        }
        result.put("city", city);
        result.put("district", nodeText(component.path("district")));
        result.put("township", nodeText(component.path("township")));
        result.put("address", nodeText(regeocode.path("formatted_address")));
        return result;
    }

    /**
     * 查询路线。
     *
     * @param from 起点
     * @param to 终点
     * @param city 城市
     * @param mode driving、walking、cycling 或 transit
     * @param preference 路线偏好
     * @param coordinateSystem 输入坐标系
     * @return 标准路线字段
     */
    public Map<String, Object> route(
            String from,
            String to,
            String city,
            String mode,
            String preference,
            String coordinateSystem) {
        String normalizedMode = normalizeMode(mode);
        String origin = resolvePoint(from, city, coordinateSystem);
        String destination = resolvePoint(to, city, coordinateSystem);
        URI uri;
        if ("transit".equals(normalizedMode)) {
            uri = uri("/v3/direction/transit/integrated")
                    .queryParam("key", properties.apiKey())
                    .queryParam("origin", origin)
                    .queryParam("destination", destination)
                    .queryParam("city", blankDefault(city, "全国"))
                    .queryParam("cityd", blankDefault(city, "全国"))
                    .queryParam("strategy", 0)
                    .build().encode().toUri();
        } else if ("cycling".equals(normalizedMode)) {
            uri = uri("/v4/direction/bicycling")
                    .queryParam("key", properties.apiKey())
                    .queryParam("origin", origin)
                    .queryParam("destination", destination)
                    .build().encode().toUri();
        } else if ("walking".equals(normalizedMode)) {
            uri = uri("/v3/direction/walking")
                    .queryParam("key", properties.apiKey())
                    .queryParam("origin", origin)
                    .queryParam("destination", destination)
                    .build().encode().toUri();
        } else {
            String strategy = "avoid_congestion".equalsIgnoreCase(text(preference)) ? "33" : "32";
            uri = uri("/v5/direction/driving")
                    .queryParam("key", properties.apiKey())
                    .queryParam("origin", origin)
                    .queryParam("destination", destination)
                    .queryParam("strategy", strategy)
                    // tmcs/navi make inter-city responses exceed WebFlux's default
                    // 256 KiB buffer. Cost plus polyline contains everything this UI uses.
                    .queryParam("show_fields", "cost,polyline")
                    .build().encode().toUri();
        }
        JsonNode root = get(uri, "路线规划");
        JsonNode path = firstRoute(root, normalizedMode);
        String seconds = path.path("duration").asText(path.path("cost").path("duration").asText("0"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from);
        result.put("to", to);
        result.put("from_address", displayAddress(from, origin));
        result.put("to_address", displayAddress(to, destination));
        result.put("origin", origin);
        result.put("destination", destination);
        result.put("mode", normalizedMode);
        result.put("distance_m", parseLong(path.path("distance").asText("0")));
        result.put("distance", formatDistance(path.path("distance").asText("0")));
        result.put("duration_seconds", parseLong(seconds));
        result.put("duration_minutes", Math.max(1, (parseLong(seconds) + 59) / 60));
        result.put("steps", steps(path, normalizedMode));
        result.put("route_polyline", routePolyline(path, normalizedMode));
        result.put("traffic", traffic(path));
        return result;
    }

    /**
     * 查询未来七天内的驾车耗时预测。
     *
     * @param from 起点
     * @param to 终点
     * @param city 城市
     * @param coordinateSystem 输入坐标系
     * @param departureEpoch 预计出发 Unix 秒
     * @return 标准预测路线字段
     */
    public Map<String, Object> futureDrivingRoute(
            String from,
            String to,
            String city,
            String coordinateSystem,
            long departureEpoch) {
        long now = Instant.now().getEpochSecond();
        if (departureEpoch <= now || departureEpoch > now + Duration.ofDays(7).toSeconds()) {
            throw input("未来路线出发时间必须在未来七天内。");
        }
        String origin = resolvePoint(from, city, coordinateSystem);
        String destination = resolvePoint(to, city, coordinateSystem);
        URI uri = uri("/v4/etd/driving")
                .queryParam("key", properties.apiKey())
                .queryParam("origin", origin)
                .queryParam("destination", destination)
                .queryParam("strategy", 1)
                .queryParam("firsttime", departureEpoch)
                .queryParam("interval", 1800)
                .queryParam("count", 3)
                .build().encode().toUri();
        JsonNode root = get(uri, "未来路线预测");
        JsonNode response = root.path("data").isArray() && !root.path("data").isEmpty()
                ? root.path("data").path(0) : root.path("data");
        JsonNode paths = response.path("paths");
        JsonNode timeInfos = response.path("time_infos");
        if (!paths.isArray() || paths.isEmpty() || !timeInfos.isArray() || timeInfos.isEmpty()) {
            throw upstream("高德没有返回可用的未来路线预测。");
        }
        JsonNode firstPath = paths.path(0);
        JsonNode firstElements = timeInfos.path(0).path("elements");
        if (!firstElements.isArray() || firstElements.isEmpty()) {
            throw upstream("高德未来路线缺少耗时预测。");
        }
        long durationMinutes = parseLong(firstElements.path(0).path("duration").asText("0"));
        List<Map<String, Object>> predictions = new ArrayList<>();
        int index = 0;
        for (JsonNode timeInfo : timeInfos) {
            JsonNode elements = timeInfo.path("elements");
            if (!elements.isArray() || elements.isEmpty()) {
                continue;
            }
            long startMillis = parseLong(timeInfo.path("starttime").asText("0"));
            long epoch = startMillis > 0
                    ? startMillis / 1000
                    : departureEpoch + index * 1800L;
            predictions.add(Map.of(
                    "departure_epoch", epoch,
                    "departure", Instant.ofEpochSecond(epoch)
                            .atZone(ZoneId.of("Asia/Shanghai"))
                            .format(DateTimeFormatter.ofPattern("MM月dd日 HH:mm")),
                    "duration_minutes", parseLong(elements.path(0).path("duration").asText("0"))));
            index++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from);
        result.put("to", to);
        result.put("origin", origin);
        result.put("destination", destination);
        result.put("mode", "driving");
        result.put("distance_m", parseLong(firstPath.path("distance").asText("0")));
        result.put("distance", formatDistance(firstPath.path("distance").asText("0")));
        result.put("duration_minutes", Math.max(1, durationMinutes));
        result.put("duration_seconds", Math.max(1, durationMinutes) * 60);
        result.put("steps", steps(firstPath, "driving"));
        result.put("traffic", Map.of("label", "未来路况预测"));
        result.put("predictions", predictions);
        result.put("forecast", true);
        return result;
    }

    /**
     * 查询周边 POI。
     *
     * @param keyword 关键词
     * @param location 中心地名或坐标
     * @param city 城市
     * @param limit 数量
     * @param coordinateSystem 输入坐标系
     * @return POI 列表
     */
    public List<Map<String, Object>> nearby(
            String keyword,
            String location,
            String city,
            int limit,
            String coordinateSystem) {
        requireKey();
        int capped = Math.max(1, Math.min(10, limit));
        String center = resolvePoint(blankDefault(location, city), city, coordinateSystem);
        URI uri = uri("/v5/place/around")
                .queryParam("key", properties.apiKey())
                .queryParam("location", center)
                .queryParam("keywords", blankDefault(keyword, "美食"))
                .queryParam("radius", 5000)
                .queryParam("page_size", capped)
                .queryParam("page_num", 1)
                .queryParam("show_fields", "business,photos")
                .build().encode().toUri();
        JsonNode pois = get(uri, "周边搜索").path("pois");
        List<Map<String, Object>> items = new ArrayList<>();
        if (pois.isArray()) {
            for (JsonNode poi : pois) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", poi.path("id").asText(""));
                item.put("name", poi.path("name").asText(""));
                item.put("address", nodeText(poi.path("address")));
                item.put("location", poi.path("location").asText(""));
                item.put("distance_m", parseLong(poi.path("distance").asText("0")));
                item.put("type", poi.path("type").asText(""));
                JsonNode business = poi.path("business");
                putText(item, "rating", business.path("rating"));
                putText(item, "cost", business.path("cost"));
                putText(item, "tag", business.path("tag"));
                putText(item, "telephone", business.path("tel"));
                putText(item, "open_time", business.path("opentime_today"));
                List<Map<String, Object>> photos = poiPhotos(poi.path("photos"));
                if (!photos.isEmpty()) {
                    item.put("image_url", photos.get(0).get("url"));
                    item.put("image_title", photos.get(0).get("title"));
                    item.put("photos", photos);
                }
                items.add(item);
                if (items.size() >= capped) {
                    break;
                }
            }
        }
        if (items.isEmpty()) {
            logger.warn("AMap nearby returned no POI, uri={}, nodeType={}, rawCount={}",
                    safeUri(uri), pois.getNodeType(), pois.size());
            throw new ToolExecutionException("PLACE_NOT_FOUND", "AMap nearby returned no POI",
                    false, "附近没有找到相关地点。");
        }
        return items;
    }

    private List<Map<String, Object>> poiPhotos(JsonNode node) {
        List<Map<String, Object>> photos = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return photos;
        }
        Iterable<JsonNode> values = node.isArray() ? node : List.of(node);
        for (JsonNode photo : values) {
            String url = photo.path("url").asText("").trim();
            if (url.isBlank()) {
                continue;
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("url", url);
            value.put("title", photo.path("title").asText(""));
            photos.add(value);
            if (photos.size() >= 3) {
                break;
            }
        }
        return photos;
    }

    private void putText(Map<String, Object> target, String key, JsonNode value) {
        String text = nodeText(value).trim();
        if (!text.isBlank()) {
            target.put(key, text);
        }
    }

    /**
     * 查询道路或指定位置周边的实时路况。
     *
     * @param city 城市或 adcode
     * @param road 道路名，为空时查周边
     * @param location 中心位置
     * @param radius 半径
     * @param coordinateSystem 输入坐标系
     * @return 标准路况字段
     */
    public Map<String, Object> traffic(
            String city,
            String road,
            String location,
            int radius,
            String coordinateSystem) {
        requireKey();
        boolean roadScope = !text(road).isBlank();
        UriComponentsBuilder builder;
        if (roadScope) {
            String adcode = text(city).matches("\\d{6}")
                    ? text(city)
                    : resolveAdcode(city);
            builder = uri("/v3/traffic/status/road")
                    .queryParam("key", properties.apiKey())
                    .queryParam("name", road)
                    .queryParam("adcode", adcode)
                    .queryParam("level", 5)
                    .queryParam("extensions", "all");
        } else {
            String center = resolvePoint(blankDefault(location, city), city, coordinateSystem);
            builder = uri("/v3/traffic/status/circle")
                    .queryParam("key", properties.apiKey())
                    .queryParam("location", center)
                    .queryParam("radius", Math.max(100, Math.min(5000, radius)))
                    .queryParam("level", 5)
                    .queryParam("extensions", "all");
        }
        JsonNode info = get(builder.build().encode().toUri(), "实时路况").path("trafficinfo");
        JsonNode evaluation = info.path("evaluation");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", roadScope ? "road" : "nearby");
        result.put("target", roadScope ? road : blankDefault(location, city));
        result.put("status", evaluation.path("status").asText("0"));
        result.put("label", trafficLabel(evaluation.path("status").asText("0")));
        result.put("description", info.path("description").asText(
                evaluation.path("description").asText("")));
        result.put("smooth_ratio", evaluation.path("expedite").asText(""));
        result.put("slow_ratio", evaluation.path("congested").asText(""));
        result.put("congested_ratio", evaluation.path("blocked").asText(""));
        return result;
    }

    /**
     * 查询高德天气预报，用于出发时间编排。
     *
     * @param city 城市
     * @return 天气条件与温度
     */
    public Map<String, Object> weather(String city) {
        requireKey();
        // 高德天气接口稳定接受的是行政区编码；直接传中文或英文城市名时可能返回
        // status=1 但 forecasts 为空，不能把这种空响应误报成成功天气。
        String adcode = resolveAdcode(city);
        URI uri = uri("/v3/weather/weatherInfo")
                .queryParam("key", properties.apiKey())
                .queryParam("city", adcode)
                .queryParam("extensions", "all")
                .build().encode().toUri();
        JsonNode cast = get(uri, "天气查询").path("forecasts").path(0).path("casts").path(0);
        if (cast.isMissingNode()
                || cast.path("dayweather").asText("").isBlank()
                || cast.path("daytemp").asText("").isBlank()
                || cast.path("nighttemp").asText("").isBlank()) {
            throw upstream("高德没有返回可用的天气预报。");
        }
        return Map.of(
                "condition", cast.path("dayweather").asText(),
                "day_temperature", cast.path("daytemp").asText(),
                "night_temperature", cast.path("nighttemp").asText());
    }

    private JsonNode get(URI uri, String operation) {
        try {
            String body = webClient.get().uri(uri).accept(MediaType.APPLICATION_JSON)
                    .retrieve().bodyToMono(String.class)
                    .timeout(Duration.ofMillis(properties.timeoutMs())).block();
            JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
            boolean success = "1".equals(root.path("status").asText())
                    || root.path("errcode").asInt(-1) == 0;
            if (!success) {
                String providerMessage = root.path("info")
                        .asText(root.path("errmsg").asText("unknown"));
                logger.warn("AMap request rejected, operation={}, uri={}, reason={}",
                        operation, safeUri(uri), providerMessage);
                throw new IllegalStateException(providerMessage);
            }
            return root;
        } catch (ToolExecutionException error) {
            throw error;
        } catch (Exception error) {
            throw new ToolExecutionException("UPSTREAM_FAILED",
                    "AMap " + operation + " failed: " + safeMessage(error), true,
                    operation + "暂时不可用，请稍后再试。");
        }
    }

    private String resolveAdcode(String city) {
        String normalized = blankDefault(city, "深圳");
        URI uri = uri("/v3/geocode/geo")
                .queryParam("key", properties.apiKey())
                .queryParam("address", normalized)
                .queryParam("city", normalized)
                .build().encode().toUri();
        String adcode = get(uri, "城市编码").path("geocodes").path(0)
                .path("adcode").asText("").trim();
        if (!adcode.matches("\\d{6}")) {
            throw new ToolExecutionException("PLACE_NOT_FOUND",
                    "AMap returned no adcode for " + normalized, false,
                    "没有找到城市“" + normalized + "”的道路编码。");
        }
        return adcode;
    }

    private JsonNode firstRoute(JsonNode root, String mode) {
        JsonNode paths;
        if ("cycling".equals(mode)) {
            paths = root.path("data").path("paths");
        } else if ("transit".equals(mode)) {
            paths = root.path("route").path("transits");
        } else {
            paths = root.path("route").path("paths");
        }
        if (!paths.isArray() || paths.isEmpty()) {
            throw upstream("高德没有返回可用路线。");
        }
        return paths.get(0);
    }

    private List<String> steps(JsonNode path, String mode) {
        List<String> result = new ArrayList<>();
        if ("transit".equals(mode)) {
            for (JsonNode segment : path.path("segments")) {
                for (JsonNode line : segment.path("bus").path("buslines")) {
                    String name = line.path("name").asText("").trim();
                    if (!name.isBlank()) {
                        result.add("乘坐" + name);
                    }
                }
            }
        } else {
            for (JsonNode step : path.path("steps")) {
                String instruction = step.path("instruction").asText("").trim();
                if (!instruction.isBlank()) {
                    result.add(instruction);
                }
                if (result.size() >= 6) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 提取供应商返回的真实路线折线。
     *
     * @param path 高德首条路线
     * @param mode 交通方式
     * @return 按行驶顺序排列的“经度,纬度”坐标
     */
    private List<String> routePolyline(JsonNode path, String mode) {
        List<String> points = new ArrayList<>();
        if ("transit".equals(mode)) {
            for (JsonNode segment : path.path("segments")) {
                appendPolyline(points, segment.path("walking").path("steps"));
                for (JsonNode line : segment.path("bus").path("buslines")) {
                    appendPolyline(points, line.path("polyline").asText(""));
                }
            }
        } else {
            appendPolyline(points, path.path("steps"));
        }
        return points;
    }

    private void appendPolyline(List<String> points, JsonNode steps) {
        for (JsonNode step : steps) {
            appendPolyline(points, step.path("polyline").asText(""));
        }
    }

    private void appendPolyline(List<String> points, String polyline) {
        for (String raw : text(polyline).split(";")) {
            String point = raw.trim();
            if (isCoordinate(point) && (points.isEmpty() || !point.equals(points.get(points.size() - 1)))) {
                points.add(point);
            }
        }
    }

    private String displayAddress(String input, String coordinate) {
        if (!isCoordinate(text(input))) {
            return text(input);
        }
        try {
            return String.valueOf(reverseGeocode(coordinate, "gcj02")
                    .getOrDefault("address", input));
        } catch (RuntimeException error) {
            logger.warn("AMap route address lookup failed, coordinate={}, error={}",
                    coordinate, error.getMessage());
            return text(input);
        }
    }

    private Map<String, Object> traffic(JsonNode path) {
        long total = 0;
        long congested = 0;
        for (JsonNode step : path.path("steps")) {
            for (JsonNode tmc : step.path("tmcs")) {
                long distance = parseLong(tmc.path("distance")
                        .asText(tmc.path("tmc_distance").asText("0")));
                total += distance;
                String status = tmc.path("status")
                        .asText(tmc.path("tmc_status").asText(""));
                if (status.contains("拥堵") || status.contains("严重")) {
                    congested += distance;
                }
            }
        }
        if (total <= 0) {
            return Map.of();
        }
        int ratio = (int) Math.round(congested * 100.0 / total);
        return Map.of(
                "label", ratio >= 20 ? "较拥堵" : ratio > 0 ? "有缓行" : "总体畅通",
                "congested_ratio", ratio);
    }

    private String convertCoordinate(String coordinate, String system) {
        if (!"wgs84".equalsIgnoreCase(text(system)) && !"gps".equalsIgnoreCase(text(system))) {
            return coordinate;
        }
        URI uri = uri("/v3/assistant/coordinate/convert")
                .queryParam("key", properties.apiKey())
                .queryParam("locations", coordinate)
                .queryParam("coordsys", "gps")
                .build().encode().toUri();
        String converted = get(uri, "坐标转换").path("locations").asText("").trim();
        if (!isCoordinate(converted)) {
            throw upstream("高德没有返回有效转换坐标。");
        }
        return converted;
    }

    private void requireKey() {
        if (properties.apiKey().isBlank()) {
            throw new ToolExecutionException("PROVIDER_NOT_CONFIGURED",
                    "AMAP_WEB_SERVICE_KEY is not configured", false,
                    "地图服务尚未配置。");
        }
    }

    private UriComponentsBuilder uri(String path) {
        return UriComponentsBuilder.fromUriString(properties.baseUrl() + path);
    }

    private java.util.Optional<String> optional(String value) {
        String normalized = text(value);
        return normalized.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(normalized);
    }

    private String normalizeMode(String mode) {
        return switch (text(mode).toLowerCase(Locale.ROOT)) {
            case "walking", "walk", "步行", "走路" -> "walking";
            case "cycling", "bicycling", "bike", "骑行", "骑车" -> "cycling";
            case "transit", "bus", "metro", "公交", "地铁", "公共交通" -> "transit";
            default -> "driving";
        };
    }

    private String trafficLabel(String value) {
        return switch (value) {
            case "1" -> "畅通";
            case "2" -> "缓行";
            case "3" -> "拥堵";
            case "4" -> "严重拥堵";
            default -> "未知";
        };
    }

    private boolean isCoordinate(String value) {
        if (value == null || !value.matches("-?\\d{1,3}(?:\\.\\d+)?,\\s*-?\\d{1,2}(?:\\.\\d+)?")) {
            return false;
        }
        String[] parts = value.split(",", 2);
        double longitude = Double.parseDouble(parts[0].trim());
        double latitude = Double.parseDouble(parts[1].trim());
        return longitude >= -180 && longitude <= 180 && latitude >= -90 && latitude <= 90;
    }

    private String nodeText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.isArray() ? node.path(0).asText("").trim() : node.asText("").trim();
    }

    private String formatDistance(String meters) {
        try {
            double value = Double.parseDouble(meters);
            return value >= 1000
                    ? String.format(Locale.CHINA, "%.1f公里", value / 1000)
                    : (long) value + "米";
        } catch (Exception ignored) {
            return meters;
        }
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String blankDefault(String value, String fallback) {
        return text(value).isBlank() ? text(fallback) : text(value);
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private ToolExecutionException input(String summary) {
        return new ToolExecutionException("INVALID_ARGUMENT", summary, false, summary);
    }

    private ToolExecutionException upstream(String summary) {
        return new ToolExecutionException("UPSTREAM_FAILED", summary, true, summary);
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private String safeUri(URI uri) {
        return uri.toString().replaceAll("([?&]key=)[^&]+", "$1***");
    }
}
