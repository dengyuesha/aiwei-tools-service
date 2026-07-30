package com.aiwei.tools.state;

import com.aiwei.tools.contract.ToolContext;
import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户隔离状态工具测试。
 */
class TenantStateToolExecutorTest {

    @TempDir
    Path tempDirectory;

    @Test
    void memoryIsIsolatedByTenantAndUser() {
        TenantStateRepository repository = repository();
        new MemoryWriteToolExecutor(repository).execute(request(
                "tenant-a", "user-a", Map.of("text", "喜欢深圳的咖啡店")));

        ToolExecutionResult sameUser = new MemorySearchToolExecutor(repository).execute(request(
                "tenant-a", "user-a", Map.of("query", "深圳")));
        ToolExecutionResult otherUser = new MemorySearchToolExecutor(repository).execute(request(
                "tenant-a", "user-b", Map.of("query", "深圳")));

        assertThat((java.util.List<?>) sameUser.data().get("items")).hasSize(1);
        assertThat((java.util.List<?>) otherUser.data().get("items")).isEmpty();
    }

    @Test
    void memoWriteIsIdempotent() {
        TenantStateRepository repository = repository();
        ToolInvokeRequest request = request("tenant-a", "user-a", Map.of("text", "周五续费"));
        ToolExecutionResult first = new MemoCreateToolExecutor(repository).execute(request);
        ToolExecutionResult second = new MemoCreateToolExecutor(repository).execute(request);

        assertThat(first.data().get("item")).isEqualTo(second.data().get("item"));
        assertThat(repository.list("memos", request)).hasSize(1);
    }

    @Test
    void reminderPersistsPendingDeliveryRecord() {
        TenantStateRepository repository = repository();
        ToolExecutionResult result = new ReminderCreateToolExecutor(repository)
                .execute(request("tenant-a", "user-a", Map.of("text", "十分钟后提醒我休息")));

        Map<?, ?> item = (Map<?, ?>) result.data().get("item");
        assertThat(item.get("status")).isEqualTo("scheduled");
        assertThat(item.get("delivery_status")).isEqualTo("pending");
    }

    private TenantStateRepository repository() {
        return new TenantStateRepository(
                new StateProperties(tempDirectory.toString()), new ObjectMapper());
    }

    private ToolInvokeRequest request(String tenant, String user, Map<String, Object> args) {
        return new ToolInvokeRequest("req-state", tenant, user, "session",
                args, ToolContext.empty(), "idem-1");
    }
}
