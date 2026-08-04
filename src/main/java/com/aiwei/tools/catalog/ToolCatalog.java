package com.aiwei.tools.catalog;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 从 aiweios-server 迁移而来的稳定逻辑工具目录。
 */
@Component
public class ToolCatalog {

    private final Map<String, ToolDefinition> tools;

    /**
     * 创建包含全部 28 个静态逻辑工具的目录。
     */
    public ToolCatalog() {
        Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
        add(definitions, "device.light.set", "device_control", "sync", 300, 800, false, false, true, ToolStatus.AVAILABLE);
        add(definitions, "weather.get", "information", "hybrid", 900, 8000, true, false, false, ToolStatus.AVAILABLE);
        add(definitions, "news.search", "information", "hybrid", 1400, 10000, true, false, false, ToolStatus.AVAILABLE);
        add(definitions, "flight.search", "travel", "hybrid", 9000, 25000, true, false, false, ToolStatus.AVAILABLE);
        add(definitions, "rail.search", "travel", "hybrid", 8000, 12000, true, false, false, ToolStatus.AVAILABLE);
        add(definitions, "travel.compare", "travel", "hybrid", 12000, 15000, true, false, false, ToolStatus.AVAILABLE);
        add(definitions, "travel.departure.plan", "travel", "hybrid", 12000, 15000, true, false, false, ToolStatus.AVAILABLE);
        add(definitions, "stock.quote", "information", "hybrid", 2500, 12000, true, false, false, ToolStatus.AVAILABLE);
        add(definitions, "stock.kline", "information", "hybrid", 6000, 12000, true, false, false, ToolStatus.AVAILABLE);
        add(definitions, "map.route", "travel", "hybrid", 3500, 10000, true, false, false, ToolStatus.AVAILABLE);
        add(definitions, "map.nearby", "travel", "hybrid", 3500, 10000, true, false, false, ToolStatus.AVAILABLE);
        add(definitions, "map.traffic", "travel", "hybrid", 3500, 10000, true, false, false, ToolStatus.AVAILABLE);
        add(definitions, "location.now", "information", "sync", 200, 10000, false, false, false, ToolStatus.AVAILABLE);
        add(definitions, "task.checklist.create", "personal_assistant", "sync", 700, 800, false, false, false, ToolStatus.AVAILABLE);
        add(definitions, "reminder.create", "personal_assistant", "sync", 400, 1000, false, false, true, ToolStatus.AVAILABLE);
        add(definitions, "memo.create", "personal_assistant", "sync", 500, 1000, false, false, true, ToolStatus.AVAILABLE);
        add(definitions, "mcp.time.now", "mcp_utility", "sync", 80, 500, false, false, false, ToolStatus.AVAILABLE);
        add(definitions, "mcp.calculator.eval", "mcp_utility", "sync", 120, 500, false, false, false, ToolStatus.AVAILABLE);
        add(definitions, "mcp.fetch.url", "mcp_utility", "hybrid", 1800, 8000, true, false, false, ToolStatus.AVAILABLE);
        add(definitions, "mcp.search.web", "mcp_utility", "hybrid", 8000, 10000, true, false, false, ToolStatus.AVAILABLE);
        add(definitions, "mcp.browser.operate", "mcp_utility", "async", 5000, 15000, true, false, true, ToolStatus.EXPERIMENTAL);
        add(definitions, "mcp.memory.write", "mcp_utility", "sync", 200, 1000, false, false, true, ToolStatus.AVAILABLE);
        add(definitions, "mcp.memory.search", "mcp_utility", "sync", 300, 1000, false, false, false, ToolStatus.AVAILABLE);
        add(definitions, "knowledge.search", "knowledge", "hybrid", 1100, 8000, true, false, false, ToolStatus.AVAILABLE);
        add(definitions, "itinerary.plan", "personal_assistant", "hybrid", 1300, 10000, true, false, false, ToolStatus.AVAILABLE);
        add(definitions, "meeting.prepare", "work_assistant", "hybrid", 1200, 10000, true, false, false, ToolStatus.AVAILABLE);
        add(definitions, "memory.digest", "memory", "hybrid", 900, 8000, true, false, true, ToolStatus.AVAILABLE);
        add(definitions, "music.play", "entertainment", "hybrid", 1200, 15000, true, false, true, ToolStatus.AVAILABLE);
        add(definitions, "media.share.search", "entertainment", "hybrid", 1800, 15000, true, false, false, ToolStatus.AVAILABLE);
        add(definitions, "media.share.validate", "entertainment", "hybrid", 1800, 15000, true, false, false, ToolStatus.AVAILABLE);
        this.tools = Map.copyOf(definitions);
    }

    /**
     * 查询工具定义。
     *
     * @param name 逻辑工具名
     * @return 工具定义
     */
    public Optional<ToolDefinition> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 返回全部工具定义。
     *
     * @return 不可变工具目录
     */
    public Map<String, ToolDefinition> all() {
        return tools;
    }

    private void add(
            Map<String, ToolDefinition> target,
            String name,
            String category,
            String mode,
            int sloMs,
            int timeoutMs,
            boolean background,
            boolean confirmation,
            boolean sideEffect,
            ToolStatus status) {
        target.put(name, new ToolDefinition(
                name,
                category,
                mode,
                sloMs,
                timeoutMs,
                background,
                confirmation,
                sideEffect,
                status));
    }
}
