package com.aiwei.tools.api;

import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.contract.ToolInvokeResponse;
import com.aiwei.tools.execution.ToolExecutionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 统一工具调用 API。
 */
@RestController
@RequestMapping("/api/v1/tools")
public class ToolController {

    private final ToolExecutionService executionService;

    /**
     * 创建工具控制器。
     *
     * @param executionService 统一执行服务
     */
    public ToolController(ToolExecutionService executionService) {
        this.executionService = executionService;
    }

    /**
     * 调用指定逻辑工具。
     *
     * @param toolName 逻辑工具名
     * @param request 调用请求
     * @return 标准工具响应
     */
    @PostMapping(
            value = "/{toolName}/invoke",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ToolInvokeResponse> invoke(
            @PathVariable String toolName,
            @RequestBody ToolInvokeRequest request) {
        return executionService.invoke(toolName, request);
    }
}

