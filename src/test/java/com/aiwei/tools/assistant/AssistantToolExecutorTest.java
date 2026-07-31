/* 2026-07-31 Codex 修改：验证多日行程保留高德返回的景点图片。 */
package com.aiwei.tools.assistant;

import com.aiwei.tools.contract.ToolContext;
import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.knowledge.KnowledgeSearchToolExecutor;
import com.aiwei.tools.memory.MemoryDigestToolExecutor;
import com.aiwei.tools.map.AmapClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 无状态个人助理、知识和记忆摘要执行器测试。
 */
class AssistantToolExecutorTest {

    @Test
    void createsChecklistFromCallerItems() {
        ToolExecutionResult result = new ChecklistToolExecutor().execute(request(Map.of(
                "topic", "发布准备", "items", List.of("跑测试", "打包", "发布"))));

        assertThat(result.provider()).isEqualTo("builtin_checklist");
        assertThat(result.summary()).contains("3 项");
    }

    @Test
    void createsItineraryWithoutInventingLiveRoute() {
        AmapClient amap = mock(AmapClient.class);
        when(amap.nearby(anyString(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(List.of(Map.of("name", "西湖", "address", "西湖区", "image_url", "https://example.com/west-lake.jpg")));
        ToolExecutionResult result = new ItineraryPlanToolExecutor(amap)
                .execute(request(Map.of("city", "杭州", "date", "tomorrow", "days", 2)));

        assertThat(result.data()).containsEntry("requires_live_route", true);
        assertThat(result.data()).containsEntry("days_count", 2);
        assertThat(result.summary()).contains("杭州");
    }

    @Test
    void preparesMeetingBrief() {
        ToolExecutionResult result = new MeetingPrepareToolExecutor()
                .execute(request(Map.of("topic", "客户方案会", "audience", "客户")));

        assertThat(result.summary()).contains("客户方案会");
        assertThat(result.data()).containsKey("agenda");
    }

    @Test
    void searchesOnlyDeclaredBuiltinKnowledgeScope() {
        ToolExecutionResult result = new KnowledgeSearchToolExecutor()
                .execute(request(Map.of("query", "独立工具服务")));

        assertThat(result.provider()).isEqualTo("builtin_aiweios_knowledge");
        assertThat(result.data()).containsEntry("scope", "builtin_aiweios_docs");
    }

    @Test
    void digestsOnlyRequestScopedMemories() {
        ToolExecutionResult result = new MemoryDigestToolExecutor().execute(request(Map.of(
                "memories", List.of("喜欢简洁回答", "经常查询深圳天气"),
                "profile", Map.of("language", "zh-CN"))));

        assertThat(result.data()).containsEntry("memory_count", 2);
        assertThat(result.provider()).isEqualTo("request_scoped_memory_digest");
    }

    private ToolInvokeRequest request(Map<String, Object> arguments) {
        return new ToolInvokeRequest("req-assistant", "default", "user", "session",
                arguments, ToolContext.empty(), null);
    }
}
