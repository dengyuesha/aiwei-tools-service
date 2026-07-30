package com.aiwei.tools.catalog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具目录完整性测试。
 */
class ToolCatalogTest {

    @Test
    void containsAllStaticAiweiosToolsAndMigratedToolsAreAvailable() {
        ToolCatalog catalog = new ToolCatalog();

        assertThat(catalog.all()).hasSize(28);
        assertThat(catalog.find("flight.search")).isPresent();
        assertThat(catalog.find("music.play")).isPresent();
        assertThat(catalog.find("mcp.time.now").orElseThrow().status()).isEqualTo(ToolStatus.AVAILABLE);
        assertThat(catalog.find("mcp.calculator.eval").orElseThrow().status()).isEqualTo(ToolStatus.AVAILABLE);
        assertThat(catalog.find("rail.search").orElseThrow().status()).isEqualTo(ToolStatus.AVAILABLE);
        assertThat(catalog.find("flight.search").orElseThrow().status()).isEqualTo(ToolStatus.AVAILABLE);
        assertThat(catalog.find("stock.quote").orElseThrow().status()).isEqualTo(ToolStatus.AVAILABLE);
        assertThat(catalog.find("stock.kline").orElseThrow().status()).isEqualTo(ToolStatus.AVAILABLE);
        assertThat(catalog.all().values())
                .filteredOn(tool -> tool.status() == ToolStatus.AVAILABLE)
                .extracting(ToolDefinition::name)
                .containsExactlyInAnyOrder(
                        "mcp.time.now",
                        "mcp.calculator.eval",
                        "rail.search",
                        "flight.search",
                        "stock.quote",
                        "stock.kline",
                        "travel.departure.plan",
                        "map.route",
                        "map.nearby",
                        "map.traffic",
                        "location.now",
                        "travel.compare",
                        "weather.get",
                        "news.search",
                        "mcp.search.web",
                        "mcp.fetch.url",
                        "task.checklist.create",
                        "knowledge.search",
                        "itinerary.plan",
                        "meeting.prepare",
                        "memory.digest",
                        "reminder.create",
                        "memo.create",
                        "mcp.memory.write",
                        "mcp.memory.search",
                        "device.light.set",
                        "music.play");
    }
}
