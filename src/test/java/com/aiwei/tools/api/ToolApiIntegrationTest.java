package com.aiwei.tools.api;

import com.aiwei.tools.catalog.ToolCatalog;
import com.aiwei.tools.execution.ToolExecutorRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一工具 API 端到端测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ToolApiIntegrationTest {

    @Autowired
    private ToolCatalog catalog;

    @Autowired
    private ToolExecutorRegistry executorRegistry;

    private final WebTestClient client;

    /**
     * 创建指向随机测试端口的客户端。
     *
     * @param port 随机端口
     */
    @Autowired
    ToolApiIntegrationTest(@LocalServerPort int port) {
        this.client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }

    @Test
    void listsAllStaticTools() {
        client.get()
                .uri("/api/v1/tools")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(30)
                .jsonPath("$['mcp.time.now'].status").isEqualTo("AVAILABLE")
                .jsonPath("$['flight.search'].status").isEqualTo("AVAILABLE");
    }

    @Test
    void invokesCalculatorThroughUnifiedProtocol() {
        Map<String, Object> body = Map.of(
                "requestId", "req-calculator-1",
                "tenantId", "default",
                "userId", "user-1",
                "sessionId", "session-1",
                "arguments", Map.of("expression", "20000乘以3.14减5"),
                "context", Map.of("locale", "zh-CN", "timezone", "Asia/Shanghai"));

        client.post()
                .uri("/api/v1/tools/mcp.calculator.eval/invoke")
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.provider").isEqualTo("builtin_calculator")
                .jsonPath("$.data.expression").isEqualTo("20000*3.14-5")
                .jsonPath("$.data.result").isEqualTo(62795)
                .jsonPath("$.metadata.requestId").isEqualTo("req-calculator-1");
    }

    @Test
    void invokesBundledMusicByName() {
        client.post()
                .uri("/api/v1/tools/music.play/invoke")
                .bodyValue(Map.of(
                        "requestId", "req-music-1",
                        "sessionId", "device-1",
                        "arguments", Map.of("action", "play", "query", "夏日信号")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.provider").isEqualTo("local")
                .jsonPath("$.data.matched").isEqualTo(true)
                .jsonPath("$.data.track.name").isEqualTo("夏日信号")
                .jsonPath("$.data.track.audio_url").isEqualTo("/music/summer-pop.wav");
    }

    @Test
    void returnsDeviceCommandWithoutMutatingCallerSession() {
        client.post()
                .uri("/api/v1/tools/device.light.set/invoke")
                .bodyValue(Map.of(
                        "requestId", "req-device-1",
                        "arguments", Map.of("state", "on", "scene", "night")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.provider").isEqualTo("device_command_contract")
                .jsonPath("$.data.requires_caller_dispatch").isEqualTo(true)
                .jsonPath("$.metadata.requestId").isEqualTo("req-device-1");
    }

    @Test
    void rejectsInvalidTimezoneAsInvalidArgument() {
        client.post()
                .uri("/api/v1/tools/mcp.time.now/invoke")
                .bodyValue(Map.of(
                        "requestId", "req-time-1",
                        "arguments", Map.of("timezone", "Mars/Olympus")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error.code").isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void allThirtyStaticToolsHaveExecutors() {
        assertThat(catalog.all()).hasSize(30);
        assertThat(catalog.all().keySet())
                .allMatch(name -> executorRegistry.find(name).isPresent());
    }
}
