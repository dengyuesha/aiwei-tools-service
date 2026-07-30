package com.aiwei.tools.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 服务间 API Key 鉴权测试。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "aiwei.tools.api-key=test-secret")
class ApiKeyWebFilterIntegrationTest {

    private final WebTestClient client;

    /**
     * 创建指向随机测试端口的客户端。
     *
     * @param port 随机端口
     */
    @Autowired
    ApiKeyWebFilterIntegrationTest(@LocalServerPort int port) {
        this.client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }

    @Test
    void protectsApiButKeepsHealthPublic() {
        client.get().uri("/api/v1/tools").exchange().expectStatus().isUnauthorized();
        client.get()
                .uri("/api/v1/tools")
                .header("X-Tools-Api-Key", "test-secret")
                .exchange()
                .expectStatus().isOk();
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }
}
