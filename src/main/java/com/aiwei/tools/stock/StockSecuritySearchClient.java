/*
 * 2026-08-03 Codex 新增：通过证券搜索服务动态解析 A股、港股和美股名称，并缓存标准代码。
 */
package com.aiwei.tools.stock;

import com.aiwei.tools.execution.ToolExecutionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 腾讯证券搜索客户端。
 *
 * <p>名称解析只在代码规则无法处理时发生。成功结果缓存 24 小时，避免每次行情查询都增加
 * 一次远程请求；缓存达到上限时清理过期记录。</p>
 */
@Component
public class StockSecuritySearchClient {

    private static final long CACHE_TTL_MS = Duration.ofHours(24).toMillis();
    private static final int CACHE_MAX_ENTRIES = 2048;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String searchUrl;
    private final long timeoutMs;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 创建证券搜索客户端。
     *
     * @param webClientBuilder HTTP 客户端构建器
     * @param objectMapper JSON 字符串解码器
     * @param searchUrl 腾讯证券搜索地址
     * @param timeoutMs 搜索超时毫秒数
     */
    public StockSecuritySearchClient(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${aiwei.tools.stock.tencent-symbol-search-url:https://smartbox.gtimg.cn/s3/}") String searchUrl,
            @Value("${aiwei.tools.stock.symbol-search-timeout-ms:2500}") long timeoutMs) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
        this.searchUrl = searchUrl;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 2500L;
    }

    /**
     * 根据公司名称搜索标准股票标的。
     *
     * @param query 清理后的公司名称
     * @param marketHint hs、hk、us 或空字符串
     * @return 最匹配的股票标的
     * @throws ToolExecutionException 上游不可用或没有匹配结果时抛出
     */
    public StockSymbolResolver.StockTarget resolve(String query, String marketHint) {
        String normalizedQuery = normalizeName(query);
        String normalizedMarket = normalizeMarket(marketHint);
        String cacheKey = normalizedMarket + ":" + normalizedQuery;
        CacheEntry cached = cache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.createdAtMs() <= CACHE_TTL_MS) {
            return cached.target();
        }
        if (cached != null) {
            cache.remove(cacheKey, cached);
        }

        List<SearchCandidate> candidates = search(query).stream()
                .filter(candidate -> candidate.type().toUpperCase(Locale.ROOT).startsWith("GP"))
                .filter(candidate -> normalizedMarket.isBlank()
                        || normalizedMarket.equals(candidate.market()))
                .sorted(Comparator
                        .comparingInt((SearchCandidate candidate) -> score(candidate, normalizedQuery)).reversed()
                        .thenComparingInt(candidate -> marketPriority(candidate.market())))
                .toList();
        if (candidates.isEmpty()) {
            throw new ToolExecutionException(
                    "STOCK_SYMBOL_NOT_FOUND",
                    "No stock symbol matched name: " + query,
                    false,
                    "没有找到股票“" + query + "”，请补充市场或股票代码。");
        }

        SearchCandidate first = candidates.get(0);
        StockSymbolResolver.StockTarget target = first.toTarget();
        if (cache.size() >= CACHE_MAX_ENTRIES) {
            cache.entrySet().removeIf(entry -> now - entry.getValue().createdAtMs() > CACHE_TTL_MS);
        }
        cache.put(cacheKey, new CacheEntry(target, now));
        return target;
    }

    private List<SearchCandidate> search(String query) {
        URI uri = UriComponentsBuilder.fromUriString(searchUrl)
                .queryParam("q", query)
                .queryParam("t", "all")
                .build().encode().toUri();
        try {
            org.springframework.http.ResponseEntity<byte[]> response = webClient.get()
                    .uri(uri)
                    .accept(MediaType.TEXT_PLAIN, MediaType.ALL)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "https://gu.qq.com/")
                    .retrieve()
                    .toEntity(byte[].class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();
            byte[] bytes = response == null ? null : response.getBody();
            java.nio.charset.Charset charset = response == null
                    || response.getHeaders().getContentType() == null
                    || response.getHeaders().getContentType().getCharset() == null
                    ? java.nio.charset.StandardCharsets.UTF_8
                    : response.getHeaders().getContentType().getCharset();
            String body = bytes == null ? "" : new String(bytes, charset);
            return parse(body);
        } catch (ToolExecutionException error) {
            throw error;
        } catch (Exception error) {
            throw new ToolExecutionException(
                    "STOCK_SYMBOL_SEARCH_FAILED",
                    "Stock symbol search failed: " + safeMessage(error),
                    true,
                    "股票名称解析暂时不可用，请直接说股票代码。");
        }
    }

    private List<SearchCandidate> parse(String body) throws Exception {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        int start = body.indexOf('"');
        int end = body.lastIndexOf('"');
        if (start < 0 || end <= start) {
            return List.of();
        }
        String decoded = objectMapper.readValue(body.substring(start, end + 1), String.class);
        List<SearchCandidate> candidates = new ArrayList<>();
        for (String item : decoded.split("\\^")) {
            String[] fields = item.split("~", -1);
            if (fields.length < 5) {
                continue;
            }
            String market = normalizeMarket(fields[0]);
            String code = normalizeCode(market, fields[1]);
            if (!market.isBlank() && !code.isBlank() && !fields[2].isBlank()) {
                candidates.add(new SearchCandidate(market, code, fields[2].trim(), fields[4].trim()));
            }
        }
        return candidates;
    }

    private int score(SearchCandidate candidate, String query) {
        String name = normalizeName(candidate.name());
        if (name.equals(query) || stripShareSuffix(name).equals(stripShareSuffix(query))) return 100;
        if (name.startsWith(query)) return 75;
        if (name.contains(query)) return 60;
        return 10;
    }

    private String normalizeName(String value) {
        return String.valueOf(value == null ? "" : value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s·._-]", "")
                .trim();
    }

    private String stripShareSuffix(String value) {
        return value.replaceAll("(?i)(集团)?(?:sw|w|wr|r|adr|a|b)$", "");
    }

    private String normalizeMarket(String value) {
        String market = String.valueOf(value == null ? "" : value).toLowerCase(Locale.ROOT);
        return switch (market) {
            case "sh", "sz", "hs", "a" -> "hs";
            case "hk" -> "hk";
            case "us" -> "us";
            default -> "";
        };
    }

    private String normalizeCode(String market, String value) {
        String code = String.valueOf(value == null ? "" : value).toLowerCase(Locale.ROOT).trim();
        if ("us".equals(market)) {
            int dot = code.indexOf('.');
            return dot > 0 ? code.substring(0, dot) : code;
        }
        if ("hk".equals(market) && code.matches("\\d{1,5}")) {
            return String.format("%05d", Integer.parseInt(code));
        }
        return code;
    }

    private int marketPriority(String market) {
        return switch (market) {
            case "hs" -> 0;
            case "hk" -> 1;
            case "us" -> 2;
            default -> 3;
        };
    }

    private String safeMessage(Throwable error) {
        return error == null || error.getMessage() == null
                ? "unknown error" : error.getMessage().replaceAll("[\\r\\n]+", " ");
    }

    private record CacheEntry(StockSymbolResolver.StockTarget target, long createdAtMs) {
    }

    private record SearchCandidate(String market, String code, String name, String type) {
        private StockSymbolResolver.StockTarget toTarget() {
            String gid = switch (market) {
                case "hs" -> (code.startsWith("6") || code.startsWith("9") ? "sh" : "sz") + code;
                case "hk" -> "hk" + code;
                default -> code;
            };
            return new StockSymbolResolver.StockTarget(market, gid, gid, name);
        }
    }
}
