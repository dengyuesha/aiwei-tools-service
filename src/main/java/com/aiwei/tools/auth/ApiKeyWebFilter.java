package com.aiwei.tools.auth;

import com.aiwei.tools.config.ToolsServiceProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 使用 API Key 保护工具接口；健康检查保持公开。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiKeyWebFilter implements WebFilter {

    private static final String API_KEY_HEADER = "X-Tools-Api-Key";
    private final ToolsServiceProperties properties;

    /**
     * 创建鉴权过滤器。
     *
     * @param properties 工具服务配置
     */
    public ApiKeyWebFilter(ToolsServiceProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验服务间密钥。
     *
     * @param exchange 当前请求
     * @param chain 过滤器链
     * @return 完成信号
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/") || properties.apiKey().isBlank()) {
            return chain.filter(exchange);
        }
        String supplied = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);
        if (supplied == null || !constantTimeEquals(properties.apiKey(), supplied.trim())) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}

