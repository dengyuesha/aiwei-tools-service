package com.aiwei.tools.execution;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;

/**
 * 一个稳定逻辑工具的执行器。
 */
public interface ToolExecutor {

    /**
     * 返回执行器支持的逻辑工具名。
     *
     * @return 逻辑工具名
     */
    String toolName();

    /**
     * 执行工具。
     *
     * @param request 标准调用请求
     * @return 中立执行结果
     */
    ToolExecutionResult execute(ToolInvokeRequest request);
}

