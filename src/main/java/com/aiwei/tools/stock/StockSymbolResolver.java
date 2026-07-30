package com.aiwei.tools.stock;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 股票名称、代码和市场解析器。
 *
 * <p>常用别名和确定性代码规则放在服务端；无法可靠识别时明确报错，不使用 LLM 猜代码。</p>
 */
@Component
public class StockSymbolResolver {

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("茅台", "sh600519"), Map.entry("贵州茅台", "sh600519"),
            Map.entry("腾讯", "hk00700"), Map.entry("腾讯控股", "hk00700"),
            Map.entry("阿里", "hk09988"), Map.entry("阿里巴巴", "hk09988"),
            Map.entry("baba", "baba"), Map.entry("小米", "hk01810"),
            Map.entry("小米集团", "hk01810"), Map.entry("比亚迪", "sz002594"),
            Map.entry("byd", "sz002594"), Map.entry("苹果", "aapl"),
            Map.entry("特斯拉", "tsla"), Map.entry("微软", "msft"),
            Map.entry("英伟达", "nvda"), Map.entry("谷歌", "goog"),
            Map.entry("亚马逊", "amzn"), Map.entry("中际旭创", "sz300308"),
            Map.entry("宁德时代", "sz300750"), Map.entry("中国平安", "sh601318"));

    private static final Pattern HS = Pattern.compile("^(sh|sz)(\\d{6})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HK = Pattern.compile("^hk(\\d{5})$", Pattern.CASE_INSENSITIVE);

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
        String alias = ALIASES.get(keyword);
        if (alias != null && !alias.equals(keyword)) {
            return resolve(alias);
        }
        Matcher hs = HS.matcher(keyword);
        if (hs.matches()) {
            String gid = hs.group(1).toLowerCase(Locale.ROOT) + hs.group(2);
            return new StockTarget("hs", gid, gid, displayName(keyword));
        }
        Matcher hk = HK.matcher(keyword);
        if (hk.matches()) {
            String gid = "hk" + hk.group(1);
            return new StockTarget("hk", gid, gid, displayName(keyword));
        }
        if (keyword.matches("\\d{6}")) {
            String gid = (keyword.startsWith("6") || keyword.startsWith("9") ? "sh" : "sz") + keyword;
            return new StockTarget("hs", gid, gid, gid);
        }
        if (keyword.matches("[a-z]{1,8}")) {
            return new StockTarget("us", keyword, keyword.toUpperCase(Locale.ROOT), keyword.toUpperCase(Locale.ROOT));
        }
        throw new IllegalArgumentException(
                "无法识别股票「" + raw + "」，请使用股票代码或完整名称");
    }

    private String displayName(String keyword) {
        return ALIASES.entrySet().stream()
                .filter(entry -> entry.getValue().equalsIgnoreCase(keyword))
                .map(Map.Entry::getKey)
                .filter(name -> !name.matches("[a-z]+"))
                .max(java.util.Comparator.comparingInt(String::length))
                .orElse(keyword);
    }

    private String normalize(String raw) {
        return String.valueOf(raw == null ? "" : raw)
                .replaceAll("^(查|查询|查一下|帮我查|帮我|看看|一下|的)+", "")
                .replaceAll("(股价|股票|行情|多少钱|价格|市值|K线|kline|走势)$", "")
                .replaceAll("^(美国|美股|港股|a股|A股)", "")
                .replaceAll("\\s+", "")
                .trim()
                .toLowerCase(Locale.ROOT);
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
