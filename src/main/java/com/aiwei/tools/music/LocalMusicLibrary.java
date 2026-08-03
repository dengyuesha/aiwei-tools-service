package com.aiwei.tools.music;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 内置原创试听曲目录，供尚未接入正式音乐库时稳定点播。
 */
@Component
public class LocalMusicLibrary {

    private static final List<LocalTrack> TRACKS = List.of(
            new LocalTrack("local-city-pop", "霓虹心跳", "城市流行试听集",
                    "city-pop.wav", 20, List.of("流行", "城市", "律动", "默认")),
            new LocalTrack("local-summer-pop", "夏日信号", "城市流行试听集",
                    "summer-pop.wav", 20, List.of("流行", "轻快", "夏天", "开心")),
            new LocalTrack("local-night-drive", "夜航电台", "城市流行试听集",
                    "night-drive-pop.wav", 20, List.of("流行", "夜晚", "通勤", "放松")));

    private final LocalMusicProperties properties;

    /**
     * 创建内置音乐目录。
     *
     * @param properties 本地音乐配置
     */
    public LocalMusicLibrary(LocalMusicProperties properties) {
        this.properties = properties;
    }

    /**
     * 按歌名或氛围词选择曲目；未命中时随机选择，并返回后续播放队列。
     *
     * @param query 用户点播词
     * @param count 队列长度
     * @return 本地选择结果；关闭本地音乐时返回空
     */
    public LocalSelection select(String query, int count) {
        if (!properties.enabled() || TRACKS.isEmpty()) {
            return null;
        }
        String keyword = normalize(query);
        LocalTrack matchedTrack = TRACKS.stream()
                .filter(track -> track.matches(keyword))
                .findFirst()
                .orElse(null);
        boolean matched = matchedTrack != null;
        LocalTrack selected = matched ? matchedTrack
                : TRACKS.get(ThreadLocalRandom.current().nextInt(TRACKS.size()));
        List<LocalTrack> ordered = new ArrayList<>();
        ordered.add(selected);
        TRACKS.stream().filter(track -> track != selected).forEach(ordered::add);
        List<Map<String, Object>> queue = ordered.stream()
                .limit(Math.max(1, Math.min(count, TRACKS.size())))
                .map(this::toPayload)
                .toList();
        return new LocalSelection(matched, queue.get(0), queue);
    }

    private Map<String, Object> toPayload(LocalTrack track) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("music_id", track.id());
        payload.put("name", track.name());
        payload.put("album", track.album());
        payload.put("artist", "aiweiOS 原创");
        payload.put("duration", track.durationSeconds());
        payload.put("audio_url", properties.publicBaseUrl() + "/music/" + track.fileName());
        payload.put("source", "local");
        return payload;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s《》〈〉\"'，。！？、]", "");
    }

    private record LocalTrack(
            String id,
            String name,
            String album,
            String fileName,
            int durationSeconds,
            List<String> keywords) {

        private boolean matches(String query) {
            if (query.isBlank() || "random".equals(query) || "随机".equals(query)) {
                return false;
            }
            String normalizedName = name.toLowerCase(Locale.ROOT).replaceAll("\\s", "");
            return normalizedName.contains(query) || query.contains(normalizedName)
                    || keywords.stream().anyMatch(keyword -> query.contains(keyword));
        }
    }

    /**
     * 本地点播选择结果。
     *
     * @param matched 是否精确命中点播词
     * @param track 当前曲目
     * @param queue 播放队列
     */
    public record LocalSelection(
            boolean matched,
            Map<String, Object> track,
            List<Map<String, Object>> queue) {
    }
}
