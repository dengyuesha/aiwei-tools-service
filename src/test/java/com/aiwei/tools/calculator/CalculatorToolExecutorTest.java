package com.aiwei.tools.calculator;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CalculatorToolExecutor} 输入规范化回归测试。
 */
class CalculatorToolExecutorTest {

    /**
     * 带括号的完整表达式不能被截成内部子表达式。
     */
    @Test
    void preservesParenthesizedExpressionFromNaturalRequest() {
        ToolInvokeRequest request = new ToolInvokeRequest(
                "request-1", "default", "user-1", "session-1",
                Map.of("expression", "帮我算一下(12+8)*3/nothink"),
                null, "");

        ToolExecutionResult result = new CalculatorToolExecutor().execute(request);

        assertThat(result.summary()).isEqualTo("(12+8)*3 = 60");
        assertThat(result.data()).containsEntry("expression", "(12+8)*3");
    }
}
