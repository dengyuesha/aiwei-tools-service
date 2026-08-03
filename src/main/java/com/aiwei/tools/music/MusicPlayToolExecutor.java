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
    private final LocalMusicLibrary localMusicLibrary;

    /**
     * 创建音乐执行器。
     *
     * @param client HIFIVE 客户端
     * @param localMusicLibrary 内置本地音乐目录
     */
    public MusicPlayToolExecutor(HifiveClient client, LocalMusicLibrary localMusicLibrary) {
        this.client = client;
        this.localMusicLibrary = localMusicLibrary;
    }

    @Override
    public String toolName() {
        return "music.play";
    }

    @Override
    public ToolExecutionResult execute(ToolInvokeRequest request) {
        String action = String.valueOf(request.arguments().getOrDefault("action", "play")).trim();
        if ("stop".equalsIgnoreCase(action)) {
            return new ToolExecutionResult("local", "已停止播放。",
                    Map.of("type", "music_player", "action", "stop"), false);
        }
        String query = String.valueOf(request.arguments().getOrDefault("query", "")).trim();
        int count = number(request.arguments().get("count"));
        LocalMusicLibrary.LocalSelection local = localMusicLibrary.select(query, count);
        if (local != null) {
            String name = String.valueOf(local.track().getOrDefault("name", "音乐"));
            String summary = local.matched()
                    ? "正在为您播放《" + name + "》。"
                    : (query.isBlank() ? "为您随机播放《" + name + "》。"
                    : "没有找到《" + query + "》，为您随机播放《" + name + "》。");
            return new ToolExecutionResult("local", summary,
                    Map.of("type", "music_player", "action", "play", "query", query,
                            "matched", local.matched(), "track", local.track(),
                            "queue", local.queue(), "requires_caller_playback", true), false);
        }
        if (query.isBlank()) {
            query = "轻音乐";
        }
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
