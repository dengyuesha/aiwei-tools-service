package com.aiwei.tools.calculator;

/**
 * 安全四则运算求值器，支持加减乘除、取模、括号和科学计数法。
 */
public final class SimpleMathEvaluator {

    private SimpleMathEvaluator() {
    }

    /**
     * 计算表达式。
     *
     * @param expression 已规范化的算术表达式
     * @return 计算值
     * @throws IllegalArgumentException 表达式不完整或包含意外内容时抛出
     */
    public static double evaluate(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("empty expression");
        }
        Parser parser = new Parser(expression.replaceAll("\\s+", ""));
        double value = parser.parseExpression();
        if (!parser.done()) {
            throw new IllegalArgumentException("unexpected trailing input");
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("calculator result is not finite");
        }
        return value;
    }

    private static final class Parser {
        private final String input;
        private int position;

        private Parser(String input) {
            this.input = input;
        }

        private boolean done() {
            return position >= input.length();
        }

        private double parseExpression() {
            double value = parseTerm();
            while (position < input.length()) {
                char operator = input.charAt(position);
                if (operator != '+' && operator != '-') {
                    break;
                }
                position++;
                double right = parseTerm();
                value = operator == '+' ? value + right : value - right;
            }
            return value;
        }

        private double parseTerm() {
            double value = parseFactor();
            while (position < input.length()) {
                char operator = input.charAt(position);
                if (operator != '*' && operator != '/' && operator != '%') {
                    break;
                }
                position++;
                double right = parseFactor();
                value = switch (operator) {
                    case '*' -> value * right;
                    case '/' -> value / right;
                    default -> value % right;
                };
            }
            return value;
        }

        private double parseFactor() {
            if (position >= input.length()) {
                throw new IllegalArgumentException("unexpected end of expression");
            }
            char current = input.charAt(position);
            if (current == '+') {
                position++;
                return parseFactor();
            }
            if (current == '-') {
                position++;
                return -parseFactor();
            }
            if (current == '(') {
                position++;
                double value = parseExpression();
                if (position >= input.length() || input.charAt(position) != ')') {
                    throw new IllegalArgumentException("missing closing parenthesis");
                }
                position++;
                return value;
            }
            return parseNumber();
        }

        private double parseNumber() {
            int start = position;
            boolean seenDot = false;
            boolean seenDigit = false;
            while (position < input.length()) {
                char current = input.charAt(position);
                if (Character.isDigit(current)) {
                    seenDigit = true;
                    position++;
                } else if (current == '.' && !seenDot) {
                    seenDot = true;
                    position++;
                } else if ((current == 'e' || current == 'E') && seenDigit) {
                    position++;
                    if (position < input.length()
                            && (input.charAt(position) == '+' || input.charAt(position) == '-')) {
                        position++;
                    }
                    int exponentStart = position;
                    while (position < input.length() && Character.isDigit(input.charAt(position))) {
                        position++;
                    }
                    if (position == exponentStart) {
                        throw new IllegalArgumentException("invalid scientific notation");
                    }
                    break;
                } else {
                    break;
                }
            }
            if (!seenDigit || start == position) {
                throw new IllegalArgumentException("expected number at position " + start);
            }
            return Double.parseDouble(input.substring(start, position));
        }
    }
}

