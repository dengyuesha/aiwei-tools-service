package com.aiwei.tools.state;

import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 按租户和用户分文件保存状态，避免旧实现的全局 JSONL 数据串用。
 */
@Repository
public class TenantStateRepository {

    private final Path baseDirectory;
    private final ObjectMapper objectMapper;

    /**
     * 创建状态仓库。
     *
     * @param properties 状态配置
     * @param objectMapper JSON 编解码器
     */
    public TenantStateRepository(StateProperties properties, ObjectMapper objectMapper) {
        this.baseDirectory = Path.of(properties.dataDirectory()).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等地追加一条状态记录。
     *
     * @param kind 数据类型
     * @param request 调用请求
     * @param fields 业务字段
     * @return 已存在或新建的记录
     */
    public synchronized Map<String, Object> append(
            String kind, ToolInvokeRequest request, Map<String, Object> fields) {
        Path file = file(kind, request);
        String idempotencyKey = clean(request.idempotencyKey());
        List<Map<String, Object>> existing = read(file);
        if (!idempotencyKey.isBlank()) {
            for (Map<String, Object> item : existing) {
                if (idempotencyKey.equals(String.valueOf(item.getOrDefault("idempotency_key", "")))) {
                    return item;
                }
            }
        }
        Map<String, Object> item = new LinkedHashMap<>(fields);
        item.putIfAbsent("id", kind + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        item.put("tenant_id", request.tenantId());
        item.put("user_id", request.userId());
        item.put("created_at", Instant.now().toString());
        if (!idempotencyKey.isBlank()) {
            item.put("idempotency_key", idempotencyKey);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, objectMapper.writeValueAsString(item) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return Map.copyOf(item);
        } catch (Exception error) {
            throw new ToolExecutionException("STATE_WRITE_FAILED", error.getMessage(),
                    true, "保存失败，请稍后再试。");
        }
    }

    /**
     * 读取当前租户用户的同类记录。
     *
     * @param kind 数据类型
     * @param request 调用请求
     * @return 按写入顺序返回的记录
     */
    public synchronized List<Map<String, Object>> list(String kind, ToolInvokeRequest request) {
        return List.copyOf(read(file(kind, request)));
    }

    private Path file(String kind, ToolInvokeRequest request) {
        String tenant = clean(request.tenantId());
        String user = clean(request.userId());
        if (tenant.isBlank() || user.isBlank()) {
            throw new ToolExecutionException("IDENTITY_REQUIRED",
                    "tenantId and userId are required for stateful tools", false,
                    "这个功能需要有效的用户身份。");
        }
        String scope = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((tenant + "\n" + user).getBytes(StandardCharsets.UTF_8));
        String safeKind = kind.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path resolved = baseDirectory.resolve(scope).resolve(safeKind + ".jsonl").normalize();
        if (!resolved.startsWith(baseDirectory)) {
            throw new ToolExecutionException("INVALID_STATE_PATH", "invalid state path",
                    false, "状态存储路径不正确。");
        }
        return resolved;
    }

    private List<Map<String, Object>> read(Path file) {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> result = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    result.add(objectMapper.readValue(line, new TypeReference<>() { }));
                }
            }
            return result;
        } catch (Exception error) {
            throw new ToolExecutionException("STATE_READ_FAILED", error.getMessage(),
                    true, "读取保存内容失败，请稍后再试。");
        }
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
