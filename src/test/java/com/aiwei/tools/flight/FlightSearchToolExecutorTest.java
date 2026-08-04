package com.aiwei.tools.flight;

import com.aiwei.tools.contract.ToolContext;
import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实航班查询协议和标准化测试。
 */
class FlightSearchToolExecutorTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void queriesJuheAndReturnsUiNeutralFlights() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/flight", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] response = """
                    {
                      "error_code":0,
                      "result":{"flightInfo":[{
                        "flightNo":"CA1883",
                        "departureTime":"08:30",
                        "arrivalTime":"10:45",
                        "airlineName":"中国国航",
                        "ticketPrice":"¥860",
                        "status":"计划"
                      }]}
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        FlightProperties properties = new FlightProperties(
                "juhe", "", "", "", "", "", "",
                "flight-key",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/flight",
                3000);
        FlightSearchToolExecutor executor = new FlightSearchToolExecutor(
                properties,
                new FlightLocationResolver(),
                new ObjectMapper(),
                WebClient.builder());
        ToolInvokeRequest request = new ToolInvokeRequest(
                "req-flight",
                "default",
                "user-1",
                "session-1",
                Map.of(
                        "from", "北京",
                        "to", "上海",
                        "date", LocalDate.now().plusDays(1).toString()),
                ToolContext.empty(),
                null);

        ToolExecutionResult result = executor.execute(request);

        assertThat(result.provider()).isEqualTo("juhe_flight_query");
        assertThat(result.summary()).contains("CA1883", "08:30", "10:45");
        assertThat(result.data()).containsEntry("from", "北京").containsEntry("to", "上海");
        assertThat(result.data()).containsEntry(
                "query_url",
                "https://m.ctrip.com/html5/flight/swift/index");
        assertThat((java.util.List<?>) result.data().get("flights")).hasSize(1);
        assertThat(query.get()).contains("departure=PEK", "arrival=PVG");
    }
}
