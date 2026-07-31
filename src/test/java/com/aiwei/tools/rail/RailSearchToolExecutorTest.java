/*
 * 2026-07-31 Codex 修改：补充 12306 降级数据的车站和余票字段解析测试。
 */
package com.aiwei.tools.rail;

import com.aiwei.tools.contract.ToolContext;
import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 火车票真实数据转换测试，使用本地 HTTP Server 替代第三方接口。
 */
class RailSearchToolExecutorTest {

    private HttpServer server;
    private String endpoint;
    private final AtomicReference<String> rawQuery = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/train", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            String response = """
                    {
                      "error_code": 0,
                      "reason": "success",
                      "result": [
                        {
                          "start_train_code": "G25",
                          "from_station": "北京南",
                          "to_station": "上海虹桥",
                          "start_time": "18:04",
                          "arrive_time": "22:32",
                          "lishi": "04:28",
                          "prices": [
                            {"seat_name":"二等座","num":"有","price":"627"},
                            {"seat_name":"一等座","num":"无","price":"1003"}
                          ]
                        }
                      ]
                    }
                    """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/train";
    }

    @Test
    void parsesOfficialStationList() {
        Map<String, String> stations = RailSearchToolExecutor.parseOfficialStationCodes(
                "var station_names ='@szb|深圳北|IOQ|shenzhenbei|szb|0@gzn|广州南|IZQ|guangzhounan|gzn|1';");

        assertThat(stations).containsEntry("深圳北", "IOQ").containsEntry("广州南", "IZQ");
    }

    @Test
    void normalizesOfficialTicketRow() {
        String[] fields = new String[34];
        java.util.Arrays.fill(fields, "");
        fields[3] = "G4576";
        fields[8] = "02:16";
        fields[9] = "02:45";
        fields[10] = "00:29";
        fields[30] = "3";
        fields[31] = "有";

        Map<String, Object> train = RailSearchToolExecutor.normalizeOfficialTrain(
                String.join("|", fields), "深圳北", "广州南");

        assertThat(train).containsEntry("train_no", "G4576")
                .containsEntry("depart", "02:16")
                .containsEntry("arrive", "02:45")
                .containsEntry("duration", "00:29")
                .containsEntry("train_type", "高铁");
        assertThat(train.get("seats")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("二等座", "3张")
                .containsEntry("一等座", "有票");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void callsProviderAndReturnsUiNeutralRailData() {
        RailSearchToolExecutor executor = executor("test-key");
        ToolInvokeRequest request = new ToolInvokeRequest(
                "req-rail-1",
                "default",
                "user-1",
                "session-1",
                Map.of(
                        "fromStation", "北京市",
                        "toStation", "上海市",
                        "date", LocalDate.now().plusDays(1).toString(),
                        "trainFilterFlags", "G"),
                ToolContext.empty(),
                null);

        ToolExecutionResult result = executor.execute(request);

        assertThat(result.provider()).isEqualTo("juhe_train_query");
        assertThat(result.summary()).contains("G25", "最低票价约627元");
        assertThat(result.data()).containsEntry("from", "北京").containsEntry("to", "上海");
        assertThat(result.data().get("trains")).asList().hasSize(1);
        assertThat(rawQuery.get()).contains("departure_station=%E5%8C%97%E4%BA%AC");
        assertThat(rawQuery.get()).doesNotContain("%25E5");
    }

    @Test
    void missingKeyFailsInsteadOfReturningDemoTickets() {
        RailSearchToolExecutor executor = executor("");
        ToolInvokeRequest request = new ToolInvokeRequest(
                "req-rail-2",
                "default",
                "user-1",
                "session-1",
                Map.of("from", "北京", "to", "上海"),
                ToolContext.empty(),
                null);

        assertThatThrownBy(() -> executor.execute(request))
                .isInstanceOf(ToolExecutionException.class)
                .extracting(error -> ((ToolExecutionException) error).code())
                .isEqualTo("PROVIDER_NOT_CONFIGURED");
    }

    @Test
    void rejectsDatesOutsideProviderWindowBeforeCallingProvider() {
        RailSearchToolExecutor executor = executor("test-key");
        ToolInvokeRequest request = new ToolInvokeRequest(
                "req-rail-3",
                "default",
                "user-1",
                "session-1",
                Map.of(
                        "from", "北京",
                        "to", "上海",
                        "date", LocalDate.now().plusDays(16).toString()),
                ToolContext.empty(),
                null);

        assertThatThrownBy(() -> executor.execute(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("within 15 days");
    }

    private RailSearchToolExecutor executor(String apiKey) {
        RailProperties properties = new RailProperties(apiKey, endpoint, 3000, 15);
        return new RailSearchToolExecutor(properties, WebClient.builder(), new ObjectMapper());
    }
}
