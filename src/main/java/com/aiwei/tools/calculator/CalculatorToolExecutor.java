package com.aiwei.tools.calculator;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安全计算器工具执行器。
 */
@Component
public class CalculatorToolExecutor implements ToolExecutor {

    private static final Pattern FULL_EXPRESSION =
            Pattern.compile("\\d+(?:\\.\\d+)?(?:[+\\-*/%]\\d+(?:\\.\\d+)?)+");
    private static final Pattern SAFE_EXPRESSION = Pattern.compile("[\\d+\\-*/%().Ee]+");

    /**
     * 返回稳定逻辑工具名。
     *
     * @return mcp.calculator.eval
     */
    @Override
    public String toolName() {
        return "mcp.calculator.eval";
    }

    /**
     * 规范化并计算用户表达式。
     *
     * @param request 标准请求
     * @return 计算结果
     * @throws IllegalArgumentException 表达式为空或不安全时抛出
     */
    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        Object raw = request.arguments().getOrDefault(
                "expression",
                request.arguments().getOrDefault("text", ""));
        String expression = normalizeExpression(String.valueOf(raw));
        if (expression.isBlank() || !SAFE_EXPRESSION.matcher(expression).matches()) {
            throw new IllegalArgumentException("calculator expression contains unsupported characters");
        }
        double value = SimpleMathEvaluator.evaluate(expression);
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
        String result = rounded.toPlainString();
        return new ToolExecutionResult(
                "builtin_calculator",
                expression + " = " + result,
                Map.of("expression", expression, "result", rounded),
                false);
    }

    private String normalizeExpression(String expression) {
        String normalized = expression == null ? "" : expression.trim();
        normalized = normalized
                .replace("×", "*")
                .replace("÷", "/")
                .replace("％", "%")
                .replace("，", "")
                .replace(",", "")
                .replace("乘以", "*")
                .replace("除以", "/")
                .replace("减去", "-")
                .replace("加", "+")
                .replace("减", "-")
                .replace("乘", "*")
                .replace("除", "/")
                .replaceAll("等于(?:多少|几)?|是多少|结果是多少|多少钱|多少|几", "")
                .replaceAll("快点|赶快|赶紧|立刻|马上|谢谢|拜托", "")
                .replaceAll("[啊呀呢吗嘛吧啦哟哦哇嗯哈欸诶]+", "")
                .replaceAll("[？?。！!，,、～~\\s]+", "");
        Matcher full = FULL_EXPRESSION.matcher(normalized);
        if (full.find()) {
            return full.group();
        }
        Matcher safe = SAFE_EXPRESSION.matcher(normalized);
        return safe.find() ? safe.group() : "";
    }
}

