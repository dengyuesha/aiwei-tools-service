package com.aiwei.tools.state;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 创建持久化提醒记录；到点投递由后续通知工作进程消费。
 */
@Component
public class ReminderCreateToolExecutor implements ToolExecutor {

    private static final Pattern RELATIVE =
            Pattern.compile("([一二三四五六七八九十两\\d]{1,6})\\s*(秒|分钟|小时)(?:钟)?(?:后|之后)");

    private final TenantStateRepository repository;

    /**
     * 创建提醒执行器。
     *
     * @param repository 状态仓库
     */
    public ReminderCreateToolExecutor(TenantStateRepository repository) {
        this.repository = repository;
    }

    @Override
    public String toolName() {
        return "reminder.create";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        String text = String.valueOf(request.arguments().getOrDefault("text", "提醒事项")).trim();
        Instant dueAt = resolveDueAt(request.arguments(), text);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("text", text.isBlank() ? "提醒事项" : text);
        Object kind = request.arguments().getOrDefault("kind", "reminder");
        fields.put("kind", kind == null ? "reminder" : String.valueOf(kind));
        fields.put("due_at", dueAt.toString());
        fields.put("status", "scheduled");
        fields.put("delivery_status", "pending");
        Map<String, Object> item = repository.append("reminders", request, fields);
        return new ToolExecutionResult("tenant_reminder_outbox",
                "提醒已保存，计划时间为 " + dueAt + "。", Map.of(
                "type", "reminder", "item", item,
                "delivery", "由通知工作进程消费 pending 记录"), false);
    }

    private Instant resolveDueAt(Map<String, Object> args, String text) {
        Object epoch = args.get("due_at_epoch_ms");
        if (epoch != null && !String.valueOf(epoch).isBlank()) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(String.valueOf(epoch)));
            } catch (NumberFormatException ignored) {
                // 继续尝试 ISO 时间和相对时间。
            }
        }
        String dueAt = String.valueOf(args.getOrDefault("due_at", "")).trim();
        if (!dueAt.isBlank()) {
            try {
                return Instant.parse(dueAt);
            } catch (Exception ignored) {
                // 继续尝试相对时间。
            }
        }
        Matcher matcher = RELATIVE.matcher(text);
        if (matcher.find()) {
            long amount = number(matcher.group(1));
            return switch (matcher.group(2)) {
                case "秒" -> Instant.now().plusSeconds(amount);
                case "小时" -> Instant.now().plusSeconds(amount * 3600);
                default -> Instant.now().plusSeconds(amount * 60);
            };
        }
        throw new ToolExecutionException("DUE_AT_REQUIRED",
                "due_at, due_at_epoch_ms or relative time is required", false,
                "请说明提醒时间，例如十分钟后。");
    }

    private long number(String value) {
        if (value.chars().allMatch(Character::isDigit)) {
            return Long.parseLong(value);
        }
        Map<Character, Integer> digits = Map.of(
                '一', 1, '二', 2, '三', 3, '四', 4, '五', 5,
                '六', 6, '七', 7, '八', 8, '九', 9, '两', 2);
        if ("十".equals(value)) {
            return 10;
        }
        int ten = value.indexOf('十');
        if (ten >= 0) {
            int tens = ten == 0 ? 1 : digits.getOrDefault(value.charAt(ten - 1), 0);
            int units = ten == value.length() - 1 ? 0 : digits.getOrDefault(value.charAt(ten + 1), 0);
            return tens * 10L + units;
        }
        return digits.getOrDefault(value.charAt(0), 0);
    }
}
