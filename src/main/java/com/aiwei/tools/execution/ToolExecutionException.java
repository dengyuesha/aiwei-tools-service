package com.aiwei.tools.execution;

/**
 * 执行器可预期的标准业务异常。
 */
public class ToolExecutionException extends RuntimeException {

    private final String code;
    private final boolean retryable;
    private final String userSummary;

    /**
     * 创建标准工具异常。
     *
     * @param code 稳定错误码
     * @param message 仅供系统和日志使用的错误信息
     * @param retryable 是否适合重试
     * @param userSummary 可安全返回用户的提示
     */
    public ToolExecutionException(
            String code,
            String message,
            boolean retryable,
            String userSummary) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.userSummary = userSummary;
    }

    /**
     * 返回稳定错误码。
     *
     * @return 错误码
     */
    public String code() {
        return code;
    }

    /**
     * 返回是否适合重试。
     *
     * @return 是否可重试
     */
    public boolean retryable() {
        return retryable;
    }

    /**
     * 返回用户安全提示。
     *
     * @return 用户提示
     */
    public String userSummary() {
        return userSummary;
    }
}

