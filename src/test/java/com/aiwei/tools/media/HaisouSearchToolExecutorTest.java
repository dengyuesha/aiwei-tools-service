package com.aiwei.tools.media;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HaisouSearchToolExecutorTest {

    @Test
    void repeatedSearchUsesSuccessfulResultCache() {
        HaisouClient client = mock(HaisouClient.class);
        HaisouClient.SearchResult upstream = new HaisouClient.SearchResult(
                List.of(Map.of("title", "猴子捞月", "shareUrl", "https://pan.quark.cn/s/example")),
                1, 20, 1, 1);
        when(client.search("猴子捞月", List.of(), "title", 1, 20, 0, 0)).thenReturn(upstream);
        HaisouSearchToolExecutor executor = new HaisouSearchToolExecutor(client);
        ToolInvokeRequest request = request("猴子捞月");

        ToolExecutionResult first = executor.execute(request);
        ToolExecutionResult second = executor.execute(request);

        assertThat(first.cached()).isFalse();
        assertThat(second.cached()).isTrue();
        assertThat(second.data().get("items")).isEqualTo(upstream.items());
        verify(client, times(1)).search("猴子捞月", List.of(), "title", 1, 20, 0, 0);
    }

    private ToolInvokeRequest request(String query) {
        return new ToolInvokeRequest(
                "request-1", "tenant-1", "user-1", "session-1",
                Map.of("query", query, "searchIn", "title", "page", 1, "pageSize", 20),
                null, null);
    }
}
