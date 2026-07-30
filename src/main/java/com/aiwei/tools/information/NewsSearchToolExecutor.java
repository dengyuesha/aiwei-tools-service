package com.aiwei.tools.information;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutor;
import com.aiwei.tools.web.WebSearchToolExecutor;
import org.springframework.stereotype.Component;

/**
 * 复用真实网页搜索数据源的新闻查询执行器。
 */
@Component
public class NewsSearchToolExecutor implements ToolExecutor {

    private final WebSearchToolExecutor searchExecutor;

    /**
     * 创建新闻查询执行器。
     *
     * @param searchExecutor 网页搜索执行器
     */
    public NewsSearchToolExecutor(WebSearchToolExecutor searchExecutor) {
        this.searchExecutor = searchExecutor;
    }

    @Override
    public String toolName() {
        return "news.search";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        return searchExecutor.executeSearch(request, true);
    }
}
