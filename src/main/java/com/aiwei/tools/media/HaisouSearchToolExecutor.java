package com.aiwei.tools.media;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 海搜结构化分享搜索工具。仅返回公开索引，不负责下载或保存业务选择。
 */
@Component
public class HaisouSearchToolExecutor implements ToolExecutor {

    private static final long FRESH_TTL_MS = 10 * 60_000L;
    private static final long STALE_TTL_MS = 60 * 60_000L;
    private static final int MAX_CACHE_ENTRIES = 200;

    private static final List<String> PLATFORMS = List.of(
            "ali", "baidu", "quark", "xunlei", "tianyi", "yidong", "115", "123", "uc");

    private final HaisouClient client;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    /** @param client 海搜客户端 */
    public HaisouSearchToolExecutor(HaisouClient client) {
        this.client = client;
    }

    @Override
    public String toolName() {
        return "media.share.search";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        String query = text(request.arguments().get("query"));
        if (query.isBlank() || query.length() > 100) {
            throw invalid("query must contain 1 to 100 characters");
        }
        List<String> platforms = list(request.arguments().get("platforms"));
        if (!PLATFORMS.containsAll(platforms)) {
            throw invalid("platforms contains unsupported value");
        }
        String searchIn = text(request.arguments().getOrDefault("searchIn", "title"));
        if (!List.of("title", "files").contains(searchIn)) {
            throw invalid("searchIn must be title or files");
        }
        int page = integer(request.arguments().get("page"), 1, 1, 10000);
        int pageSize = integer(request.arguments().get("pageSize"), 20, 1, 100);
        long minSize = longValue(request.arguments().get("minSize"), 0);
        long maxSize = longValue(request.arguments().get("maxSize"), 0);
        if (maxSize > 0 && minSize > maxSize) {
            throw invalid("minSize cannot exceed maxSize");
        }
        String cacheKey = String.join("|", query.toLowerCase(), String.join(",", platforms), searchIn,
                String.valueOf(page), String.valueOf(pageSize), String.valueOf(minSize), String.valueOf(maxSize));
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && now - cached.createdAtMs() <= FRESH_TTL_MS) {
            return response(query, cached.result(), true);
        }
        Object lock = locks.computeIfAbsent(cacheKey, ignored -> new Object());
        try {
            synchronized (lock) {
                now = System.currentTimeMillis();
                cached = cache.get(cacheKey);
                if (cached != null && now - cached.createdAtMs() <= FRESH_TTL_MS) {
                    return response(query, cached.result(), true);
                }
                try {
                    HaisouClient.SearchResult result = client.search(
                            query, platforms, searchIn, page, pageSize, minSize, maxSize);
                    pruneCache(now);
                    cache.put(cacheKey, new CacheEntry(result, now));
                    return response(query, result, false);
                } catch (RuntimeException error) {
                    if (cached != null && now - cached.createdAtMs() <= STALE_TTL_MS) {
                        return response(query, cached.result(), true);
                    }
                    throw error;
                }
            }
        } finally {
            locks.remove(cacheKey, lock);
        }
    }

    private ToolExecutionResult response(String query, HaisouClient.SearchResult result, boolean cached) {
        Map<String, Object> data = Map.of(
                "query", query,
                "items", result.items(),
                "pagination", Map.of(
                        "page", result.page(),
                        "pageSize", result.pageSize(),
                        "total", result.total(),
                        "totalPages", result.totalPages()),
                "downloadMode", "USER_BROWSER_CONFIRMATION_REQUIRED");
        return new ToolExecutionResult(
                "haisou_idatariver",
                result.items().isEmpty() ? "没有找到可用的网盘分享。" : "找到 " + result.items().size() + " 条网盘分享。",
                data,
                cached);
    }

    private void pruneCache(long now) {
        if (cache.size() < MAX_CACHE_ENTRIES) {
            return;
        }
        cache.entrySet().removeIf(entry -> now - entry.getValue().createdAtMs() > STALE_TTL_MS);
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.entrySet().stream()
                    .min(java.util.Comparator.comparingLong(entry -> entry.getValue().createdAtMs()))
                    .map(Map.Entry::getKey)
                    .ifPresent(cache::remove);
        }
    }

    private record CacheEntry(HaisouClient.SearchResult result, long createdAtMs) {
    }

    private List<String> list(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> values)) {
            throw invalid("platforms must be an array");
        }
        return values.stream().map(this::text).map(String::toLowerCase).distinct().toList();
    }

    private int integer(Object value, int fallback, int min, int max) {
        if (value == null) {
            return fallback;
        }
        try {
            int result = Integer.parseInt(String.valueOf(value));
            if (result < min || result > max) {
                throw invalid("number out of range");
            }
            return result;
        } catch (NumberFormatException error) {
            throw invalid("invalid integer");
        }
    }

    private long longValue(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            long result = Long.parseLong(String.valueOf(value));
            if (result < 0) {
                throw invalid("size cannot be negative");
            }
            return result;
        } catch (NumberFormatException error) {
            throw invalid("invalid size");
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private ToolExecutionException invalid(String message) {
        return new ToolExecutionException("INVALID_ARGUMENT", message, false, "影视搜索参数不正确。 ");
    }
}
