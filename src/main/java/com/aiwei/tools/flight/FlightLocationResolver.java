package com.aiwei.tools.flight;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * 常用城市和机场 IATA 代码解析器。
 *
 * <p>模型仅负责抽取城市名，稳定的代码转换集中在工具服务，避免调用方维护重复正则。</p>
 */
@Component
public class FlightLocationResolver {

    private static final Map<String, String> AIRPORT_CODES = Map.ofEntries(
            Map.entry("北京", "PEK"), Map.entry("北京首都", "PEK"), Map.entry("北京大兴", "PKX"),
            Map.entry("上海", "PVG"), Map.entry("上海浦东", "PVG"), Map.entry("上海虹桥", "SHA"),
            Map.entry("广州", "CAN"), Map.entry("深圳", "SZX"), Map.entry("成都", "CTU"),
            Map.entry("成都天府", "TFU"), Map.entry("重庆", "CKG"), Map.entry("杭州", "HGH"),
            Map.entry("南京", "NKG"), Map.entry("武汉", "WUH"), Map.entry("西安", "XIY"),
            Map.entry("厦门", "XMN"), Map.entry("青岛", "TAO"), Map.entry("昆明", "KMG"),
            Map.entry("长沙", "CSX"), Map.entry("郑州", "CGO"), Map.entry("天津", "TSN"),
            Map.entry("大连", "DLC"), Map.entry("沈阳", "SHE"), Map.entry("海口", "HAK"),
            Map.entry("三亚", "SYX"), Map.entry("合肥", "HFE"), Map.entry("福州", "FOC"),
            Map.entry("济南", "TNA"), Map.entry("哈尔滨", "HRB"), Map.entry("桂林", "KWL"),
            Map.entry("宁波", "NGB"), Map.entry("东京", "NRT"), Map.entry("大阪", "KIX"),
            Map.entry("纽约", "JFK"), Map.entry("伦敦", "LHR"), Map.entry("巴黎", "CDG"),
            Map.entry("新加坡", "SIN"), Map.entry("首尔", "ICN"), Map.entry("曼谷", "BKK"),
            Map.entry("香港", "HKG"), Map.entry("澳门", "MFM"), Map.entry("台北", "TPE"));

    private static final Map<String, String> CITY_CODES = Map.ofEntries(
            Map.entry("北京", "BJS"), Map.entry("上海", "SHA"), Map.entry("广州", "CAN"),
            Map.entry("深圳", "SZX"), Map.entry("成都", "CTU"), Map.entry("重庆", "CKG"),
            Map.entry("杭州", "HGH"), Map.entry("南京", "NKG"), Map.entry("武汉", "WUH"),
            Map.entry("西安", "SIA"), Map.entry("厦门", "XMN"), Map.entry("青岛", "TAO"),
            Map.entry("昆明", "KMG"), Map.entry("长沙", "CSX"), Map.entry("郑州", "CGO"),
            Map.entry("天津", "TSN"), Map.entry("大连", "DLC"), Map.entry("沈阳", "SHE"),
            Map.entry("海口", "HAK"), Map.entry("三亚", "SYX"), Map.entry("合肥", "HFE"),
            Map.entry("福州", "FOC"), Map.entry("济南", "TNA"), Map.entry("哈尔滨", "HRB"),
            Map.entry("东京", "TYO"), Map.entry("大阪", "OSA"), Map.entry("纽约", "NYC"),
            Map.entry("伦敦", "LON"), Map.entry("巴黎", "PAR"), Map.entry("香港", "HKG"));

    /**
     * 转换为机场三字码。
     *
     * @param value 城市、机场或三字码
     * @return IATA 机场码
     */
    public String airportCode(String value) {
        String keyword = normalize(value);
        if (keyword.matches("(?i)[A-Z]{3}")) {
            return keyword.toUpperCase(Locale.ROOT);
        }
        String code = bestMatch(AIRPORT_CODES, keyword);
        if (code == null) {
            throw new IllegalArgumentException("unsupported airport or city: " + keyword);
        }
        return code;
    }

    /**
     * 转换为 IATA 城市码。
     *
     * @param value 城市、机场或三字码
     * @return IATA 城市码
     */
    public String cityCode(String value) {
        String keyword = normalize(value);
        if (keyword.matches("(?i)[A-Z]{3}")) {
            String upper = keyword.toUpperCase(Locale.ROOT);
            return switch (upper) {
                case "PEK", "PKX" -> "BJS";
                case "PVG" -> "SHA";
                default -> upper;
            };
        }
        String code = bestMatch(CITY_CODES, keyword);
        if (code == null) {
            throw new IllegalArgumentException("unsupported flight city: " + keyword);
        }
        return code;
    }

    private String bestMatch(Map<String, String> source, String keyword) {
        String direct = source.get(keyword);
        if (direct != null) {
            return direct;
        }
        return source.entrySet().stream()
                .filter(entry -> keyword.contains(entry.getKey()) || entry.getKey().contains(keyword))
                .min(java.util.Comparator.comparingInt(entry -> entry.getKey().length()))
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    private String normalize(String value) {
        return String.valueOf(value == null ? "" : value)
                .replaceAll("^(查|查询|查一下|帮我查|帮我|看看|订|买|我要|想要)+", "")
                .replaceAll("(今天|明天|后天|市|省|特别行政区|自治区|航班|机票)", "")
                .replaceAll("\\s+", "")
                .trim();
    }
}
