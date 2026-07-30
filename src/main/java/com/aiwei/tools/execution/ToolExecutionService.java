package com.aiwei.tools.execution;

import com.aiwei.tools.catalog.ToolCatalog;
import com.aiwei.tools.catalog.ToolDefinition;
import com.aiwei.tools.contract.ToolError;
import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.contract.ToolInvokeResponse;
import com.aiwei.tools.contract.ToolResponseMetadata;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.DateTimeException;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * 统一执行工具并负责超时、线程隔离和错误归一化。
 */
@Service
public class ToolExecutionService {

    private final ToolCatalog catalog;
    private final ToolExecutorRegistry executorRegistry;

    /**
     * 创建执行服务。
     *
     * @param catalog 工具目录
     * @param executorRegistry 执行器注册表
     */
    public ToolExecutionService(ToolCatalog catalog, ToolExecutorRegistry executorRegistry) {
        this.catalog = catalog;
        this.executorRegistry = executorRegistry;
    }

    /**
     * 调用一个逻辑工具。
     *
     * <p>第三方 SDK 和遗留执行器可能阻塞，因此统一放到 boundedElastic，避免占用 WebFlux
     * 事件线程。</p>
     *
     * @param toolName 逻辑工具名
     * @param request 标准请求
     * @return 始终返回标准成功或失败对象
     */
    public Mono<ToolInvokeResponse> invoke(String toolName, ToolInvokeRequest request) {
        long startedAt = System.nanoTime();
        String requestId = normalizeRequestId(request.requestId());
        ToolDefinition definition = catalog.find(toolName).orElse(null);
        if (definition == null) {
            return Mono.just(failure(
                    toolName,
                    requestId,
                    startedAt,
                    "TOOL_NOT_FOUND",
                    "unknown tool: " + toolName,
                    false,
                    "这个功能暂时不存在。"));
        }
        ToolExecutor executor = executorRegistry.find(toolName).orElse(null);
        if (executor == null) {
            return Mono.just(failure(
                    toolName,
                    requestId,
                    startedAt,
                    "TOOL_NOT_IMPLEMENTED",
                    "tool executor is not migrated yet",
                    false,
                    "这个功能正在迁移中，暂时还不能使用。"));
        }
        return Mono.fromCallable(() -> executor.execute(request))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofMillis(definition.timeoutMs()))
                .map(result -> success(toolName, requestId, startedAt, result))
                .onErrorResume(error -> Mono.just(mapFailure(toolName, requestId, startedAt, error)));
    }

    private ToolInvokeResponse success(
            String toolName,
            String requestId,
            long startedAt,
            ToolExecutionResult result) {
        ToolResponseMetadata metadata = new ToolResponseMetadata(
                requestId,
                elapsedMs(startedAt),
                result.cached());
        return ToolInvokeResponse.success(toolName, result, metadata);
    }

    private ToolInvokeResponse mapFailure(
            String toolName,
            String requestId,
            long startedAt,
            Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof TimeoutException) {
            return failure(
                    toolName,
                    requestId,
                    startedAt,
                    "TOOL_TIMEOUT",
                    "tool execution timed out",
                    true,
                    "查询超时了，请稍后再试。");
        }
        if (cause instanceof ToolExecutionException toolError) {
            return failure(
                    toolName,
                    requestId,
                    startedAt,
                    toolError.code(),
                    safeMessage(toolError),
                    toolError.retryable(),
                    toolError.userSummary());
        }
        if (cause instanceof IllegalArgumentException || cause instanceof DateTimeException) {
            return failure(
                    toolName,
                    requestId,
                    startedAt,
                    "INVALID_ARGUMENT",
                    safeMessage(cause),
                    false,
                    "参数不正确，请换个说法再试。");
        }
        return failure(
                toolName,
                requestId,
                startedAt,
                "TOOL_EXECUTION_FAILED",
                safeMessage(cause),
                true,
                "工具执行失败，请稍后再试。");
    }

    private ToolInvokeResponse failure(
            String toolName,
            String requestId,
            long startedAt,
            String code,
            String message,
            boolean retryable,
            String summary) {
        return ToolInvokeResponse.failure(
                toolName,
                summary,
                new ToolError(code, message, retryable),
                new ToolResponseMetadata(requestId, elapsedMs(startedAt), false));
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current != current.getCause()) {
            current = current.getCause();
        }
        return current;
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private String normalizeRequestId(String requestId) {
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId.trim();
    }

    private long elapsedMs(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
