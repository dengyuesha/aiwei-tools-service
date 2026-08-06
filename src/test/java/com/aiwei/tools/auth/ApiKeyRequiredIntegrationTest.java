package com.aiwei.tools.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "aiwei.tools.api-key=")
class ApiKeyRequiredIntegrationTest {

    private final WebTestClient client;

    @Autowired
    ApiKeyRequiredIntegrationTest(@LocalServerPort int port) {
        this.client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }

    @Test
    void refusesApiTrafficWhenServiceKeyIsNotConfigured() {
        client.get()
                .uri("/api/v1/tools")
                .exchange()
                .expectStatus().isEqualTo(503);
        client.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }
}
