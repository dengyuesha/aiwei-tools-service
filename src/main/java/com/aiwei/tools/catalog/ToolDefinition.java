package com.aiwei.tools.catalog;

/**
 * 稳定逻辑工具的目录元数据。
 *
 * @param name 逻辑工具名
 * @param category 分类
 * @param defaultMode 默认执行模式
 * @param latencySloMs 延迟目标
 * @param timeoutMs 服务端总超时
 * @param canRunBackground 是否允许后台执行
 * @param requiresConfirmation 是否需要用户确认
 * @param sideEffect 是否有副作用
 * @param status 当前迁移状态
 */
public record ToolDefinition(
        String name,
        String category,
        String defaultMode,
        int latencySloMs,
        int timeoutMs,
        boolean canRunBackground,
        boolean requiresConfirmation,
        boolean sideEffect,
        ToolStatus status) {
}

