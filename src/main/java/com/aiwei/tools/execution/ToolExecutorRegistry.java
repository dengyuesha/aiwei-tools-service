package com.aiwei.tools.execution;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 收集并校验所有工具执行器。
 */
@Component
public class ToolExecutorRegistry {

    private final Map<String, ToolExecutor> executors;

    /**
     * 根据 Spring Bean 构建执行器注册表。
     *
     * @param discoveredExecutors 自动发现的执行器
     * @throws IllegalStateException 出现重复工具名时抛出
     */
    public ToolExecutorRegistry(List<ToolExecutor> discoveredExecutors) {
        Map<String, ToolExecutor> registered = new LinkedHashMap<>();
        for (ToolExecutor executor : discoveredExecutors) {
            ToolExecutor existing = registered.putIfAbsent(executor.toolName(), executor);
            if (existing != null) {
                throw new IllegalStateException("duplicate tool executor: " + executor.toolName());
            }
        }
        this.executors = Map.copyOf(registered);
    }

    /**
     * 查询执行器。
     *
     * @param toolName 逻辑工具名
     * @return 执行器
     */
    public Optional<ToolExecutor> find(String toolName) {
        return Optional.ofNullable(executors.get(toolName));
    }
}

