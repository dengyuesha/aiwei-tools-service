package com.aiwei.tools.media;

import com.aiwei.tools.execution.ToolExecutionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * iDataRiver 海搜 API 客户端。付费类请求刻意不做自动重试。
 */
@Component
public class HaisouClient {

    private static final Map<String, Set<String>> PLATFORM_HOSTS = Map.ofEntries(
            Map.entry("ali", Set.of("alipan.com", "aliyundrive.com")),
            Map.entry("baidu", Set.of("pan.baidu.com")),
            Map.entry("quark", Set.of("pan.quark.cn")),
            Map.entry("xunlei", Set.of("pan.xunlei.com")),
            Map.entry("tianyi", Set.of("cloud.189.cn")),
            Map.entry("yidong", Set.of("caiyun.139.com")),
            Map.entry("115", Set.of("115.com")),
            Map.entry("123", Set.of("123pan.com", "123684.com")),
            Map.entry("uc", Set.of("drive.uc.cn")));

    private final HaisouProperties properties;
    private final HaisouQuotaGuard quotaGuard;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    /**
     * 创建客户端。
     *
     * @param properties 海搜配置
     * @param quotaGuard 免费额度门禁
     * @param objectMapper JSON 工具
     * @param builder HTTP 客户端构建器
     */
    public HaisouClient(
            HaisouProperties properties,
            HaisouQuotaGuard quotaGuard,
            ObjectMapper objectMapper,
            WebClient.Builder builder) {
        this.properties = properties;
        this.quotaGuard = quotaGuard;
        this.objectMapper = objectMapper;
        this.webClient = builder.build();
    }

    /**
     * 搜索公开分享索引。
     *
     * @param query 关键词
     * @param platforms 网盘平台
     * @param searchIn title 或 files
     * @param page 页码
     * @param pageSize 每页数量
     * @param minSize 最小字节数
     * @param maxSize 最大字节数
     * @return 结构化搜索结果
     */
    public SearchResult search(
            String query,
            List<String> platforms,
            String searchIn,
            int page,
            int pageSize,
            long minSize,
            long maxSize) {
        requireConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        if (!platforms.isEmpty()) {
            body.put("platforms", platforms);
        }
        body.put("searchIn", searchIn);
        body.put("page", page);
        body.put("pageSize", pageSize);
        if (minSize > 0) {
            body.put("minSize", minSize);
        }
        if (maxSize > 0) {
            body.put("maxSize", maxSize);
        }
        JsonNode result = invoke(properties.searchEndpoint(), body).path("result");
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode item : result.path("items")) {
            Map<String, Object> normalized = normalizeItem(item);
            if (!normalized.isEmpty()) {
                items.add(normalized);
            }
        }
        JsonNode pagination = result.path("pagination");
        return new SearchResult(
                List.copyOf(items),
                pagination.path("page").asInt(page),
                pagination.path("pageSize").asInt(pageSize),
                pagination.path("total").asLong(items.size()),
                pagination.path("totalPages").asInt(items.isEmpty() ? 0 : 1));
    }

    /**
     * 检测一个受支持网盘分享是否仍有效。
     *
     * @param url 分享地址
     * @param password 可选提取码
     * @return 检测结果
     */
    public ValidationResult validate(String url, String password) {
        requireConfigured();
        requireSupportedShareUrl(url, null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        if (password != null && !password.isBlank()) {
            body.put("pwd", password.trim());
        }
        JsonNode result = invoke(properties.validateEndpoint(), body).path("result");
        return new ValidationResult(
                result.path("valid").asBoolean(false),
                result.path("status").asText("unknown"),
                result.path("reason").asText(""));
    }

    private JsonNode invoke(String endpoint, Map<String, Object> body) {
        int quotaUsed = quotaGuard.reserve();
        try {
            String response = webClient.post()
                    .uri(uri -> uri.scheme(URI.create(endpoint).getScheme())
                            .host(URI.create(endpoint).getHost())
                            .port(URI.create(endpoint).getPort())
                            .path(URI.create(endpoint).getPath())
                            .queryParam("apikey", properties.apiKey())
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(properties.timeoutMs()))
                    .block();
            JsonNode root = objectMapper.readTree(response == null ? "{}" : response);
            if (root.path("code").asInt(-1) != 0) {
                throw providerFailure(root, quotaUsed);
            }
            return root;
        } catch (ToolExecutionException error) {
            throw error;
        } catch (Exception error) {
            throw new ToolExecutionException(
                    "HAISOU_UPSTREAM_FAILED",
                    "haisou request failed after quota reservation " + quotaUsed + ": " + error.getMessage(),
                    false,
                    "影视搜索服务暂时不可用，本次请求不会自动重试。 ");
        }
    }

    private ToolExecutionException providerFailure(JsonNode root, int quotaUsed) {
        int code = root.path("code").asInt(-1);
        String message = root.path("msg").asText("provider rejected request");
        String stableCode = code == 1005 ? "HAISOU_CREDITS_UNAVAILABLE" : "HAISOU_PROVIDER_REJECTED";
        String userSummary = code == 1005
                ? "海搜账户余额或免费额度不可用，请检查 API Key 和平台账户。"
                : "影视搜索服务拒绝了本次请求，请稍后检查服务配置。";
        return new ToolExecutionException(
                stableCode,
                "haisou code=" + code + " quotaUsed=" + quotaUsed + " message=" + message,
                false,
                userSummary);
    }

    private Map<String, Object> normalizeItem(JsonNode item) {
        String platform = item.path("platform").asText("").trim().toLowerCase();
        String shareUrl = item.path("shareUrl").asText("").trim();
        if (!requireSupportedShareUrl(shareUrl, platform)) {
            return Map.of();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", item.path("hsid").asText(""));
        value.put("platform", platform);
        value.put("platformName", item.path("platformName").asText(platform));
        value.put("title", item.path("title").asText("未命名资源"));
        value.put("shareUrl", shareUrl);
        value.put("shareCode", item.path("shareCode").asText(""));
        value.put("sharePassword", item.path("sharePwd").asText(""));
        value.put("fileCount", Math.max(0, item.path("fileCount").asInt(0)));
        value.put("sizeBytes", Math.max(0, item.path("sizeBytes").asLong(0)));
        return Map.copyOf(value);
    }

    private boolean requireSupportedShareUrl(String value, String platform) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getHost() == null) {
                if (platform == null) {
                    throw invalidUrl();
                }
                return false;
            }
            String host = uri.getHost().toLowerCase();
            Set<String> allowed = platform == null
                    ? PLATFORM_HOSTS.values().stream().flatMap(Set::stream).collect(java.util.stream.Collectors.toSet())
                    : PLATFORM_HOSTS.getOrDefault(platform, Set.of());
            boolean accepted = allowed.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
            if (!accepted && platform == null) {
                throw invalidUrl();
            }
            return accepted;
        } catch (IllegalArgumentException error) {
            if (platform == null) {
                throw invalidUrl();
            }
            return false;
        }
    }

    private ToolExecutionException invalidUrl() {
        return new ToolExecutionException(
                "UNSUPPORTED_SHARE_URL",
                "unsupported or unsafe share URL",
                false,
                "这个分享链接不属于当前支持的网盘。 ");
    }

    private void requireConfigured() {
        if (properties.apiKey().isBlank()) {
            throw new ToolExecutionException(
                    "HAISOU_NOT_CONFIGURED",
                    "HAISOU_API_KEY is empty",
                    false,
                    "影视搜索服务尚未配置 API Key。 ");
        }
    }

    /** 搜索分页结果。 */
    public record SearchResult(List<Map<String, Object>> items, int page, int pageSize, long total, int totalPages) {
    }

    /** 分享检测结果。 */
    public record ValidationResult(boolean valid, String status, String reason) {
    }
}
