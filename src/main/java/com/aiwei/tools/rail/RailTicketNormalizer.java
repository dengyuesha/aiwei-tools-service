package com.aiwei.tools.rail;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把聚合火车票响应转换为稳定领域结构。
 */
public final class RailTicketNormalizer {

    private RailTicketNormalizer() {
    }

    /**
     * 标准化单个车次。
     *
     * @param ticket 聚合接口车次节点
     * @return 稳定车次字段
     */
    public static Map<String, Object> normalize(JsonNode ticket) {
        Map<String, Object> train = new LinkedHashMap<>();
        String trainNo = firstNonBlank(ticket, "start_train_code", "train_no", "station_train_code");
        train.put("train_no", trainNo);
        train.put("from_station", firstNonBlank(ticket, "from_station", "departure_station", "from"));
        train.put("to_station", firstNonBlank(ticket, "to_station", "arrival_station", "to"));
        train.put("depart", firstNonBlank(ticket, "start_time", "departure_time", "depart"));
        train.put("arrive", firstNonBlank(ticket, "arrive_time", "arrival_time", "arrive"));
        train.put("duration", firstNonBlank(ticket, "lishi", "duration"));
        train.put("train_type", trainTypeLabel(trainNo));
        Map<String, String> seats = new LinkedHashMap<>();
        for (JsonNode price : ticket.path("prices")) {
            String seatName = text(price, "seat_name", "席位");
            String quantity = text(price, "num", "--");
            JsonNode priceNode = price.path("price");
            String priceValue = priceNode.isMissingNode() || priceNode.isNull() ? "" : priceNode.asText("");
            seats.put(seatName, formatSeat(quantity, priceValue));
        }
        train.put("seats", seats);
        return train;
    }

    /**
     * 从车次席位中找到最低可见价格。
     *
     * @param train 标准车次
     * @return 最低价格；没有价格时为空
     */
    public static BigDecimal cheapestPrice(Map<String, Object> train) {
        Object seatsObject = train.get("seats");
        if (!(seatsObject instanceof Map<?, ?> seats)) {
            return null;
        }
        BigDecimal cheapest = null;
        for (Object value : seats.values()) {
            String text = String.valueOf(value);
            if (text.contains("无票")) {
                continue;
            }
            int marker = text.indexOf('¥');
            if (marker < 0 || marker == text.length() - 1) {
                continue;
            }
            try {
                BigDecimal price = new BigDecimal(text.substring(marker + 1).trim());
                if (cheapest == null || price.compareTo(cheapest) < 0) {
                    cheapest = price;
                }
            } catch (NumberFormatException ignored) {
                // 上游偶尔会返回“--”等非数字价格，忽略该席位但保留车次。
            }
        }
        return cheapest;
    }

    private static String firstNonBlank(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("").trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? fallback : value;
    }

    private static String formatSeat(String quantity, String price) {
        String availability = switch (quantity) {
            case "有" -> "有票";
            case "无", "--", "" -> "无票";
            case "候补" -> "候补";
            default -> quantity.contains("票") ? quantity : quantity + "票";
        };
        return price.isBlank() ? availability : availability + " ¥" + price;
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
            default -> "普速";
        };
    }
}

