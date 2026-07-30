package com.aiwei.tools.contract;

/**
 * 工具调用失败详情。
 *
 * @param code 稳定错误码
 * @param message 面向系统的简短错误信息
 * @param retryable 是否适合重试
 */
public record ToolError(String code, String message, boolean retryable) {
}

