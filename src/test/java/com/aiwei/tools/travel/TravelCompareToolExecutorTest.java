/* 2026-07-31 Codex 修改：验证出行比较同时包含真实驾车方案。 */
package com.aiwei.tools.travel;

import com.aiwei.tools.contract.ToolContext;
import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.flight.FlightSearchToolExecutor;
import com.aiwei.tools.rail.RailSearchToolExecutor;
import com.aiwei.tools.map.AmapClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 航班与火车票组合对比测试。
 */
class TravelCompareToolExecutorTest {

    @Test
    void comparesTwoRealExecutorResultsWithoutDemoData() {
        FlightSearchToolExecutor flight = mock(FlightSearchToolExecutor.class);
        RailSearchToolExecutor rail = mock(RailSearchToolExecutor.class);
        AmapClient amap = mock(AmapClient.class);
        when(flight.execute(any())).thenReturn(new ToolExecutionResult(
                "flight-provider", "航班结果。",
                Map.of("flights", List.of(Map.of("flight_no", "CA100", "price", "800"))), false));
        when(rail.execute(any())).thenReturn(new ToolExecutionResult(
                "rail-provider", "火车结果。",
                Map.of("trains", List.of(Map.of(
                        "train_no", "G100",
                        "seats", List.of(Map.of("name", "二等座", "price", "550"))))), false));
        when(amap.route(any(), any(), any(), any(), any(), any())).thenReturn(Map.of(
                "distance", "1200公里", "duration_minutes", 270));

        ToolExecutionResult result = new TravelCompareToolExecutor(flight, rail, amap)
                .execute(request(Map.of("from", "北京", "to", "上海", "date", "tomorrow")));

        assertThat(result.provider()).isEqualTo("travel_compare");
        assertThat(result.summary()).contains("航班结果", "火车结果");
        assertThat(((Map<?, ?>) result.data().get("cheapest_flight")).get("price"))
                .isEqualTo(new java.math.BigDecimal("800"));
        assertThat(((Map<?, ?>) result.data().get("cheapest_rail")).get("price"))
                .isEqualTo(new java.math.BigDecimal("550"));
        assertThat(result.data()).containsKey("driving");
        assertThat(((Map<?, ?>) result.data().get("driving")).get("duration"))
                .isEqualTo("4小时30分钟");
    }

    private ToolInvokeRequest request(Map<String, Object> arguments) {
        return new ToolInvokeRequest("req-compare", "default", "user", "session",
                arguments, ToolContext.empty(), null);
    }
}
