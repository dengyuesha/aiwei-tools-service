/* 2026-08-01 Codex 新增：覆盖跨城地点移除设备城市限定后的地理编码重试。 */
package com.aiwei.tools.map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证设备城市不能阻断跨城目的地地理编码。 */
class AmapClientCrossCityGeocodeTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void retriesCrossCityPlaceWithoutDeviceCityRestriction() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/geocode/geo", exchange -> {
            requests.incrementAndGet();
            boolean restricted = exchange.getRequestURI().getRawQuery().contains("city=");
            String body = restricted
                    ? "{\"status\":\"0\",\"info\":\"ENGINE_RESPONSE_DATA_ERROR\"}"
                    : "{\"status\":\"1\",\"geocodes\":[{\"location\":\"113.2691,23.0049\"}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        AmapClient client = new AmapClient(
                new MapProperties("test-key", "http://127.0.0.1:" + server.getAddress().getPort(), 3000),
                new ObjectMapper(), WebClient.builder());

        assertThat(client.resolvePoint("广州南站", "深圳", "gcj02"))
                .isEqualTo("113.2691,23.0049");
        assertThat(requests).hasValue(2);
    }
}
