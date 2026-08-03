/*
 * 2026-08-03 Codex 修改：增加 Nasdaq 美股历史行情回退地址。
 */
package com.aiwei.tools.stock;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 股票行情供应商配置。
 *
 * @param juheApiKey 聚合股票 API Key
 * @param juheHsUrl A 股行情地址
 * @param juheHkUrl 港股行情地址
 * @param juheUsUrl 美股行情地址
 * @param eastMoneyKlineUrl 东方财富 K 线地址
 * @param nasdaqHistoricalUrl Nasdaq 美股历史行情地址模板
 * @param timeoutMs 单次行情请求超时
 * @param maxBars 最大 K 线数量
 */
@ConfigurationProperties(prefix = "aiwei.tools.stock")
public record StockProperties(
        String juheApiKey,
        String juheHsUrl,
        String juheHkUrl,
        String juheUsUrl,
        String tencentQuoteUrl,
        String tencentKlineUrl,
        String eastMoneyKlineUrl,
        String nasdaqHistoricalUrl,
        long timeoutMs,
        int maxBars) {

    /**
     * 规范化股票配置默认值。
     */
    public StockProperties {
        juheApiKey = text(juheApiKey, "");
        juheHsUrl = text(juheHsUrl, "https://web.juhe.cn/finance/stock/hs");
        juheHkUrl = text(juheHkUrl, "https://web.juhe.cn/finance/stock/hk");
        juheUsUrl = text(juheUsUrl, "https://web.juhe.cn/finance/stock/usa");
        tencentQuoteUrl = text(tencentQuoteUrl, "https://qt.gtimg.cn/q=");
        tencentKlineUrl = text(
                tencentKlineUrl,
                "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get");
        eastMoneyKlineUrl = text(
                eastMoneyKlineUrl,
                "https://push2his.eastmoney.com/api/qt/stock/kline/get");
        nasdaqHistoricalUrl = text(
                nasdaqHistoricalUrl,
                "https://api.nasdaq.com/api/quote/{symbol}/historical");
        timeoutMs = timeoutMs > 0 ? timeoutMs : 4000L;
        maxBars = maxBars > 0 ? Math.min(maxBars, 300) : 120;
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
