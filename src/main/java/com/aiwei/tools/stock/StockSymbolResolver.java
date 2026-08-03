/*
 * 2026-08-03 Codex 修改：股票名称改由证券搜索动态解析，不再维护公司名称白名单。
 * 2026-08-03 Codex 修改：从带周期和动作词的自然口语中提取 A股、港股、美股标的。
 */
package com.aiwei.tools.stock;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 股票名称、代码和市场解析器。
 *
 * <p>股票代码使用本地确定性规则；公司名称交给证券搜索服务并缓存。无法可靠识别时明确报错，
 * 不使用 LLM 猜代码，也不在业务代码里维护有限的公司名单。</p>
 */
@Component
public class StockSymbolResolver {

    private static final Pattern HS = Pattern.compile("^(sh|sz)(\\d{6})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HK = Pattern.compile("^hk(\\d{5})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMBEDDED_HS = Pattern.compile("(?i)(sh|sz)\\d{6}");
    private static final Pattern EMBEDDED_HK = Pattern.compile("(?i)hk\\d{5}");
    private static final Pattern EMBEDDED_BARE_CODE = Pattern.compile("(?<!\\d)\\d{6}(?!\\d)");
    private static final Pattern EMBEDDED_HK_MARKET_CODE = Pattern.compile(
            "(?i)(?:港股|香港股票|港交所)\\s*0?(\\d{4,5})");
    private static final Pattern EMBEDDED_US_TICKER = Pattern.compile(
            "(?i)(?:美股|美国股票|纳斯达克|纽交所)?\\s*([a-z]{1,8})(?=\\s*(?:股票|股价|行情|k线|走势|$))");
    private final StockSecuritySearchClient securitySearchClient;

    /**
     * 创建股票标的解析器。
     *
     * @param securitySearchClient 动态证券名称搜索客户端
     */
    public StockSymbolResolver(StockSecuritySearchClient securitySearchClient) {
        this.securitySearchClient = securitySearchClient;
    }

    /**
     * 解析股票标的。
     *
     * @param raw 股票名称或代码
     * @return 标准市场和代码
     */
    public StockTarget resolve(String raw) {
        String keyword = normalize(raw);
        if (keyword.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        Matcher hs = HS.matcher(keyword);
        if (hs.matches()) {
            String gid = hs.group(1).toLowerCase(Locale.ROOT) + hs.group(2);
            return new StockTarget("hs", gid, gid, gid);
        }
        Matcher hk = HK.matcher(keyword);
        if (hk.matches()) {
            String gid = "hk" + hk.group(1);
            return new StockTarget("hk", gid, gid, gid);
        }
        if (keyword.matches("\\d{6}")) {
            String gid = (keyword.startsWith("6") || keyword.startsWith("9") ? "sh" : "sz") + keyword;
            return new StockTarget("hs", gid, gid, gid);
        }
        if (keyword.matches("[a-z]{1,8}")) {
            return new StockTarget("us", keyword, keyword.toUpperCase(Locale.ROOT), keyword.toUpperCase(Locale.ROOT));
        }
        return securitySearchClient.resolve(keyword, marketHint(raw));
    }

    private String normalize(String raw) {
        String source = String.valueOf(raw == null ? "" : raw)
                .replaceAll("(?i)/nothink", "")
                .trim();
        if (source.isBlank()) {
            return "";
        }

        Matcher hsCode = EMBEDDED_HS.matcher(source);
        if (hsCode.find()) {
            return hsCode.group().toLowerCase(Locale.ROOT);
        }
        Matcher hkCode = EMBEDDED_HK.matcher(source);
        if (hkCode.find()) {
            return hkCode.group().toLowerCase(Locale.ROOT);
        }
        Matcher hkMarketCode = EMBEDDED_HK_MARKET_CODE.matcher(source);
        if (hkMarketCode.find()) {
            return "hk" + String.format("%05d", Integer.parseInt(hkMarketCode.group(1)));
        }
        Matcher bareCode = EMBEDDED_BARE_CODE.matcher(source);
        if (bareCode.find()) {
            return bareCode.group();
        }
        Matcher usTicker = EMBEDDED_US_TICKER.matcher(source);
        if (usTicker.find()) {
            return usTicker.group(1).toLowerCase(Locale.ROOT);
        }

        return source
                .replaceAll("[，。！？、,.!?;；:：'\"“”‘’]+", " ")
                .trim()
                .replaceAll("^(?:帮我查询一下|帮我查一下|帮我查询|帮我查|查询一下|查一下|查下|查询|查|帮我|看看|看一下|看|一下)+", "")
                .replaceAll("(?i)(最近|近)?[一二两三四五六七八九十百两\\d]+(?:个)?(?:交易日|工作日|日|天|周|星期|个月|月|年|days?|weeks?|months?|years?).*$", "")
                .replaceAll("(?i)的?(股价|股票|行情|多少钱|价格|市值|K线|kline|走势|走势图|蜡烛图|stock|quote|price)$", "")
                .replaceAll("^(美国|美股|港股|a股|A股)", "")
                .replaceAll("的$", "")
                .replaceAll("[\\s-]+", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private String marketHint(String raw) {
        String value = String.valueOf(raw == null ? "" : raw).toLowerCase(Locale.ROOT);
        if (value.matches(".*(港股|香港股票|港交所|hk\\d{1,5}).*")) return "hk";
        if (value.matches(".*(美股|美国股票|纳斯达克|纽交所|nasdaq|nyse).*")) return "us";
        if (value.matches(".*(a股|沪股|深股|上证|深证|沪深).*")) return "hs";
        return "";
    }

    /**
     * 标准股票标的。
     *
     * @param market hs、hk 或 us
     * @param gid 聚合接口代码
     * @param displaySymbol 展示代码
     * @param displayName 展示名称
     */
    public record StockTarget(
            String market,
            String gid,
            String displaySymbol,
            String displayName) {
    }
}
