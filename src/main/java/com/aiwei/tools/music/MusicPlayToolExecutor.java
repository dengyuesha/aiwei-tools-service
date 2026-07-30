package com.aiwei.tools.music;

import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import com.aiwei.tools.execution.ToolExecutionException;
import com.aiwei.tools.execution.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 查询 HIFIVE 可播放音乐并返回与 UI 无关的播放队列。
 */
@Component
public class MusicPlayToolExecutor implements ToolExecutor {

    private final HifiveClient client;

    /**
     * 创建音乐执行器。
     *
     * @param client HIFIVE 客户端
     */
    public MusicPlayToolExecutor(HifiveClient client) {
        this.client = client;
    }

    @Override
    public String toolName() {
        return "music.play";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        String query = String.valueOf(request.arguments().getOrDefault("query", "")).trim();
        if (query.isBlank()) {
            query = "轻音乐";
        }
        int count = number(request.arguments().get("count"));
        String clientId = request.sessionId() == null || request.sessionId().isBlank()
                ? "aiwei-tools-service" : request.sessionId();
        List<Map<String, Object>> tracks = client.playableTracks(query, count, clientId);
        if (tracks.isEmpty()) {
            throw new ToolExecutionException("NO_PLAYABLE_TRACKS",
                    "HIFIVE returned no playable tracks", false,
                    "没有找到可播放的歌曲，请换个关键词。");
        }
        Map<String, Object> current = tracks.get(0);
        return new ToolExecutionResult("hifive",
                "已找到《" + current.getOrDefault("name", query) + "》等 "
                        + tracks.size() + " 首可播放歌曲。",
                Map.of("type", "music_player", "query", query,
                        "track", current, "queue", tracks,
                        "requires_caller_playback", true), false);
    }

    private int number(Object value) {
        try {
            return Math.max(1, Math.min(5, Integer.parseInt(String.valueOf(value))));
        } catch (Exception ignored) {
            return 3;
        }
    }
}
