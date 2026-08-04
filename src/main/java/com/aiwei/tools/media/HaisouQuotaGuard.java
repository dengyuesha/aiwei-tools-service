package com.aiwei.tools.media;

import com.aiwei.tools.execution.ToolExecutionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * 持久化海搜每日调用计数，避免服务重启后突破免费档。
 */
@Component
public class HaisouQuotaGuard {

    private final HaisouProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 创建生产计数器。
     *
     * @param properties 海搜配置
     * @param objectMapper JSON 工具
     */
    @Autowired
    public HaisouQuotaGuard(HaisouProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, Clock.system(ZoneId.of(properties.quotaZone())));
    }

    HaisouQuotaGuard(HaisouProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 在调用供应商前预占一次额度。失败调用也计数，以最保守方式避免费用。
     *
     * @return 当日已使用次数
     */
    public synchronized int reserve() {
        LocalDate today = LocalDate.now(clock);
        Usage usage = read();
        int used = today.equals(usage.date()) ? usage.used() : 0;
        if (used >= properties.dailyFreeLimit()) {
            throw new ToolExecutionException(
                    "HAISOU_FREE_QUOTA_EXHAUSTED",
                    "daily free quota exhausted",
                    false,
                    "今天的影视搜索免费次数已经用完，明天再试。 ");
        }
        int next = used + 1;
        write(new Usage(today, next));
        return next;
    }

    private Usage read() {
        Path file = properties.quotaFile();
        if (!Files.isRegularFile(file)) {
            return new Usage(LocalDate.MIN, 0);
        }
        try {
            Map<?, ?> value = objectMapper.readValue(file.toFile(), Map.class);
            LocalDate date = LocalDate.parse(String.valueOf(value.get("date")));
            int used = Integer.parseInt(String.valueOf(value.get("used")));
            return new Usage(date, Math.max(0, used));
        } catch (Exception error) {
            throw unavailable(error);
        }
    }

    private void write(Usage usage) {
        Path file = properties.quotaFile();
        Path parent = file.toAbsolutePath().getParent();
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writeValue(temporary.toFile(), Map.of(
                    "date", usage.date().toString(),
                    "used", usage.used()));
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw unavailable(error);
        }
    }

    private ToolExecutionException unavailable(Exception error) {
        return new ToolExecutionException(
                "HAISOU_QUOTA_STATE_UNAVAILABLE",
                "cannot persist haisou quota: " + error.getMessage(),
                false,
                "影视搜索额度状态不可用，为避免产生费用，本次没有发起搜索。 ");
    }

    private record Usage(LocalDate date, int used) {
    }
}
