package com.aiwei.tools.media;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 海搜网盘分享有效性检测工具。
 */
@Component
public class HaisouValidateToolExecutor implements ToolExecutor {

    private final HaisouClient client;

    /** @param client 海搜客户端 */
    public HaisouValidateToolExecutor(HaisouClient client) {
        this.client = client;
    }

    @Override
    public String toolName() {
        return "media.share.validate";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        String url = text(request.arguments().get("url"));
        if (url.isBlank()) {
            throw new ToolExecutionException("INVALID_ARGUMENT", "url is required", false, "请提供网盘分享链接。 ");
        }
        HaisouClient.ValidationResult result = client.validate(url, text(request.arguments().get("password")));
        return new ToolExecutionResult(
                "haisou_idatariver",
                result.valid() ? "这个网盘分享目前有效。" : "这个网盘分享当前不可用。",
                Map.of("url", url, "valid", result.valid(), "status", result.status(), "reason", result.reason()),
                false);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
