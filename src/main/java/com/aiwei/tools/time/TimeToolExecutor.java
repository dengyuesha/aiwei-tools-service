package com.aiwei.tools.time;

import com.aiwei.tools.contract.ToolContext;
import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 返回指定时区的当前时间或日期。
 */
@Component
public class TimeToolExecutor implements ToolExecutor {

    /**
     * 返回稳定逻辑工具名。
     *
     * @return mcp.time.now
     */
    @Override
    public String toolName() {
        return "mcp.time.now";
    }

    /**
     * 计算指定时区的当前时间。
     *
     * @param request 标准请求
     * @return 时间结果
     * @throws IllegalArgumentException 时区无效时抛出
     */
    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        Map<String, Object> arguments = request.arguments();
        ToolContext context = request.context();
        String timezone = firstNonBlank(
                arguments.get("timezone"),
                arguments.get("tz"),
                context.timezone(),
                "Asia/Shanghai");
        String focus = firstNonBlank(arguments.get("focus"), "time").toLowerCase(Locale.ROOT);
        ZoneId zoneId = ZoneId.of(timezone);
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        String summary = formatSummary(now, focus);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timezone", zoneId.getId());
        data.put("focus", focus);
        data.put("date", now.toLocalDate().toString());
        data.put("time", now.toLocalTime().withNano(0).toString());
        data.put("iso", now.toInstant().toString());
        data.put("weekday", now.getDayOfWeek().name());
        return new ToolExecutionResult("builtin_time", summary, data, false);
    }

    private String formatSummary(ZonedDateTime now, String focus) {
        return switch (focus) {
            case "date", "day" -> formatDate(now);
            case "datetime", "both" ->
                    formatDate(now).replace("。", "") + "，" + formatClock(now).replace("现在是", "");
            default -> formatClock(now);
        };
    }

    private String formatDate(ZonedDateTime now) {
        return String.format(
                "今天是%d年%d月%d日，%s。",
                now.getYear(),
                now.getMonthValue(),
                now.getDayOfMonth(),
                weekdayCn(now.getDayOfWeek()));
    }

    private String formatClock(ZonedDateTime now) {
        int hour = now.getHour();
        int minute = now.getMinute();
        String period;
        int displayHour;
        if (hour < 6) {
            period = "凌晨";
            displayHour = hour == 0 ? 12 : hour;
        } else if (hour < 12) {
            period = "上午";
            displayHour = hour;
        } else if (hour == 12) {
            period = "中午";
            displayHour = 12;
        } else if (hour < 18) {
            period = "下午";
            displayHour = hour - 12;
        } else {
            period = "晚上";
            displayHour = hour - 12;
        }
        if (minute == 0) {
            return String.format("现在是%s%d点整。", period, displayHour);
        }
        return String.format("现在是%s%d点%d分。", period, displayHour, minute);
    }

    private String weekdayCn(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "星期一";
            case TUESDAY -> "星期二";
            case WEDNESDAY -> "星期三";
            case THURSDAY -> "星期四";
            case FRIDAY -> "星期五";
            case SATURDAY -> "星期六";
            case SUNDAY -> "星期日";
        };
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }
}

