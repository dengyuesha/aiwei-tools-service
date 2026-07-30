package com.aiwei.tools.contract;

import java.util.Map;

/**
 * 统一工具调用响应。
 *
 * @param success 是否成功
 * @param tool 稳定逻辑工具名
 * @param provider 实际供应商
 * @param summary 可直接用于语音播报的简短文本
 * @param data 与具体 UI 无关的结构化领域数据
 * @param error 失败信息
 * @param metadata 响应元数据
 */
public record ToolInvokeResponse(
        boolean success,
        String tool,
        String provider,
        String summary,
        Map<String, Object> data,
        ToolError error,
        ToolResponseMetadata metadata) {

    /**
     * 创建成功响应。
     *
     * @param tool 工具名
     * @param result 执行结果
     * @param metadata 响应元数据
     * @return 成功响应
     */
    public static ToolInvokeResponse success(
            String tool,
            ToolExecutionResult result,
            ToolResponseMetadata metadata) {
        return new ToolInvokeResponse(
                true,
                tool,
                result.provider(),
                result.summary(),
                result.data(),
                null,
                metadata);
    }

    /**
     * 创建失败响应。
     *
     * @param tool 工具名
     * @param summary 安全的用户提示
     * @param error 错误详情
     * @param metadata 响应元数据
     * @return 失败响应
     */
    public static ToolInvokeResponse failure(
            String tool,
            String summary,
            ToolError error,
            ToolResponseMetadata metadata) {
        return new ToolInvokeResponse(false, tool, "", summary, Map.of(), error, metadata);
    }
}

