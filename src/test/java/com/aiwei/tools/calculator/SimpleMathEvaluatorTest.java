package com.aiwei.tools.calculator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 安全算术解析测试。
 */
class SimpleMathEvaluatorTest {

    @Test
    void honorsParenthesesAndOperatorPrecedence() {
        assertThat(SimpleMathEvaluator.evaluate("(2+3)*4-5%2")).isEqualTo(19.0);
    }

    @Test
    void rejectsNonArithmeticInput() {
        assertThatThrownBy(() -> SimpleMathEvaluator.evaluate("1+System.exit(0)"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonFiniteResult() {
        assertThatThrownBy(() -> SimpleMathEvaluator.evaluate("1/0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not finite");
    }
}

