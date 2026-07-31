package com.aiwei.tools.map;

import com.aiwei.tools.contract.ToolContext;
import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.information.WeatherToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 地图、定位和出发规划执行器契约测试。
 */
class MapToolExecutorTest {

    private HttpServer server;
    private AmapClient client;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/geocode/geo", exchange -> respond(exchange, """
                {"status":"1","geocodes":[{"location":"114.0579,22.5431","adcode":"440300"}]}
                """));
        server.createContext("/v5/direction/driving", exchange -> respond(exchange, """
                {"status":"1","route":{"paths":[{
                  "distance":"12000",
                  "cost":{"duration":"1800"},
                  "steps":[{"instruction":"沿深南大道向西行驶","tmcs":[
                    {"distance":"8000","status":"畅通"},
                    {"distance":"4000","status":"拥堵"}
                  ]}]
                }]}}
                """));
        server.createContext("/v4/etd/driving", exchange -> respond(exchange, """
                {"errcode":0,"data":{"paths":[
                  {"distance":"12000","steps":[{"instruction":"沿深南大道向西行驶"}]}
                ],"time_infos":[
                  {"elements":[{"duration":"35"}]},
                  {"elements":[{"duration":"38"}]}
                ]}}
                """));
        server.createContext("/v5/place/around", exchange -> respond(exchange, """
                {"status":"1","pois":[
                  {"id":"p1","name":"测试咖啡店","address":"科技园1号","location":"114.1,22.5","distance":"320","type":"餐饮",
                   "business":{"rating":"4.8","cost":"42","tag":"手冲咖啡","opentime_today":"08:00-22:00"},
                   "photos":[{"title":"门店实景","url":"https://img.example.com/coffee.jpg"}]},
                  {"id":"p2","name":"第二咖啡店","address":"科技园2号","location":"114.2,22.5","distance":"650","type":"餐饮"}
                ]}
                """));
        server.createContext("/v3/traffic/status/road", exchange -> respond(exchange, """
                {"status":"1","trafficinfo":{"description":"部分路段缓行","evaluation":{
                  "status":"2","expedite":"70%","congested":"20%","blocked":"10%"
                }}}
                """));
        server.createContext("/v3/geocode/regeo", exchange -> respond(exchange, """
                {"status":"1","regeocode":{
                  "formatted_address":"广东省深圳市南山区科技园",
                  "addressComponent":{"province":"广东省","city":"深圳市","district":"南山区","township":"粤海街道"}
                }}
                """));
        server.createContext("/v3/weather/weatherInfo", exchange -> respond(exchange, """
                {"status":"1","forecasts":[{"casts":[{"dayweather":"雷阵雨","daytemp":"31","nighttemp":"26"}]}]}
                """));
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new AmapClient(
                new MapProperties("test-key", baseUrl, 3000),
                new ObjectMapper(), WebClient.builder());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void routeReturnsNeutralDistanceDurationAndTraffic() {
        ToolExecutionResult result = new MapRouteToolExecutor(client).execute(request(
                Map.of("from", "深圳市民中心", "to", "深圳北站", "city", "深圳"),
                ToolContext.empty()));

        assertThat(result.provider()).isEqualTo("amap");
        assertThat(result.summary()).contains("12.0公里", "30分钟");
        assertThat(result.data()).containsEntry("duration_minutes", 30L);
        assertThat(((Map<?, ?>) result.data().get("traffic")).get("congested_ratio"))
                .isEqualTo(33);
    }

    @Test
    void nearbyAndTrafficUseRealAmapResponses() {
        ToolExecutionResult nearby = new MapNearbyToolExecutor(client).execute(request(
                Map.of("keyword", "咖啡", "location", "深圳科技园", "city", "深圳"),
                ToolContext.empty()));
        ToolExecutionResult traffic = new MapTrafficToolExecutor(client).execute(request(
                Map.of("road", "深南大道", "city", "440300"),
                ToolContext.empty()));

        assertThat(nearby.summary()).contains("测试咖啡店");
        assertThat((java.util.List<?>) nearby.data().get("items")).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstPlace = (Map<String, Object>)
                ((java.util.List<?>) nearby.data().get("items")).get(0);
        assertThat(firstPlace)
                .containsEntry("image_url", "https://img.example.com/coffee.jpg")
                .containsEntry("rating", "4.8")
                .containsEntry("cost", "42");
        assertThat(traffic.summary()).contains("缓行", "部分路段缓行");
    }

    @Test
    void locationUsesExplicitCallerCoordinates() {
        ToolContext context = new ToolContext(
                "深圳", "南山区", 22.5431, 114.0579,
                "gcj02", "zh-CN", "Asia/Shanghai");

        ToolExecutionResult result = new LocationNowToolExecutor(client).execute(
                request(Map.of(), context));

        assertThat(result.provider()).isEqualTo("amap_reverse_geocode");
        assertThat(result.summary()).contains("深圳市南山区科技园");
    }

    @Test
    void departurePlanCombinesRouteWeatherAndSceneBuffer() {
        long arrival = Instant.now().plusSeconds(4 * 3600).getEpochSecond();
        ToolContext context = new ToolContext(
                "深圳", "南山区", null, null,
                "gcj02", "zh-CN", "Asia/Shanghai");

        ToolExecutionResult result = new DeparturePlanToolExecutor(client).execute(request(
                Map.of(
                        "destination", "深圳北站",
                        "arrival_time", arrival,
                        "scene", "railway",
                        "from", "深圳市民中心"),
                context));

        assertThat(result.provider()).isEqualTo("amap_context_planner");
        assertThat(result.summary()).contains("预计路上35分钟", "预留55分钟", "未来路况预测");
        assertThat(result.data()).containsEntry("buffer_minutes", 55);
        assertThat(result.data()).containsEntry("forecast", true);
    }

    @Test
    void weatherReturnsRealForecastWithoutMockFallback() {
        ToolExecutionResult result = new WeatherToolExecutor(client).execute(request(
                Map.of("city", "深圳"), ToolContext.empty()));

        assertThat(result.provider()).isEqualTo("amap");
        assertThat(result.summary()).contains("雷阵雨", "26到31摄氏度");
        assertThat(result.data()).containsEntry("city", "深圳");
    }

    private ToolInvokeRequest request(Map<String, Object> arguments, ToolContext context) {
        return new ToolInvokeRequest(
                "req-map", "default", "user-1", "session-1",
                arguments, context, null);
    }

    private void respond(HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
