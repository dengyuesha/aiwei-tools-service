package com.aiwei.tools.information;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import com.aiwei.tools.map.AmapClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 使用高德真实预报数据查询天气的执行器。
 */
@Component
public class WeatherToolExecutor implements ToolExecutor {

    private final AmapClient client;

    /**
     * 创建天气执行器。
     *
     * @param client 高德客户端
     */
    public WeatherToolExecutor(AmapClient client) {
        this.client = client;
    }

    @Override
    public String toolName() {
        return "weather.get";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        Object cityArg = request.arguments().get("city");
        String city = cityArg == null ? request.context().city() : String.valueOf(cityArg);
        if (city == null || city.isBlank()) {
            throw new ToolExecutionException("INVALID_ARGUMENT", "city is required",
                    false, "请告诉我要查询哪个城市的天气。");
        }
        Map<String, Object> weather = new LinkedHashMap<>(client.weather(city.trim()));
        weather.put("city", city.trim());
        String condition = String.valueOf(weather.get("condition"));
        String low = String.valueOf(weather.get("night_temperature"));
        String high = String.valueOf(weather.get("day_temperature"));
        return new ToolExecutionResult("amap",
                city.trim() + "天气：" + condition + "，气温" + low + "到" + high + "摄氏度。",
                weather, false);
    }
}
