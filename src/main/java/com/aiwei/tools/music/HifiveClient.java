package com.aiwei.tools.music;

import com.aiwei.tools.execution.ToolExecutionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HIFIVE SearchMusic 和 ToolHQListen 客户端。
 */
@Component
public class HifiveClient {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final HifiveProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    /**
     * 创建音乐客户端。
     *
     * @param properties HIFIVE 配置
     * @param objectMapper JSON 编解码器
     * @param builder HTTP 客户端构建器
     */
    public HifiveClient(
            HifiveProperties properties,
            ObjectMapper objectMapper,
            WebClient.Builder builder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = builder.build();
    }

    /**
     * 查询并取得可播放曲目。
     *
     * @param keyword 关键词
     * @param count 数量
     * @param clientId 调用方客户端 ID
     * @return 可播放曲目
     */
    public List<Map<String, Object>> playableTracks(String keyword, int count, String clientId) {
        requireConfigured();
        Map<String, String> search = Map.of(
                "Keyword", keyword, "Page", "1", "PageSize", String.valueOf(count));
        JsonNode root = call("POST", "SearchMusic", search, clientId);
        ensureSuccess(root, "SearchMusic");
        List<Map<String, Object>> tracks = new ArrayList<>();
        for (JsonNode record : root.path("data").path("record")) {
            String musicId = record.path("musicId").asText("");
            if (musicId.isBlank()) {
                continue;
            }
            try {
                JsonNode listen = call("GET", "ToolHQListen", Map.of(
                        "MusicId", musicId, "AudioFormat", "mp3", "AudioRate", "128"), clientId);
                ensureSuccess(listen, "ToolHQListen");
                JsonNode playback = listen.path("data");
                String audioUrl = playback.path("fileUrl").asText("");
                if (!audioUrl.isBlank()) {
                    Map<String, Object> track = new LinkedHashMap<>();
                    track.put("music_id", musicId);
                    track.put("name", record.path("musicName").asText(""));
                    track.put("album", record.path("albumName").asText(""));
                    track.put("artist", artist(record));
                    track.put("duration", record.path("duration").asInt(0));
                    track.put("audio_url", audioUrl);
                    track.put("expires", playback.path("expires").asLong(0));
                    tracks.add(track);
                }
            } catch (RuntimeException ignored) {
                // 单曲不可播放时继续尝试下一首。
            }
        }
        return tracks;
    }

    private JsonNode call(
            String method, String action, Map<String, String> params, String clientId) {
        try {
            String nonce = nonce();
            String timestamp = String.valueOf(System.currentTimeMillis());
            String signature = HifiveSigner.sign(method, action, properties,
                    nonce, clientId, timestamp, params);
            WebClient.RequestHeadersSpec<?> request;
            if ("GET".equals(method)) {
                URI uri = uri(params);
                request = webClient.get().uri(uri);
            } else {
                LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                params.forEach(form::add);
                request = webClient.post().uri(uri(Map.of()))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(BodyInserters.fromFormData(form));
            }
            String response = request
                    .header("Authorization", "HF3-HMAC-SHA1 Signature=" + signature)
                    .header("X-HF-Action", action)
                    .header("X-HF-Version", properties.version())
                    .header("X-HF-AppId", properties.appId())
                    .header("X-HF-Nonce", nonce)
                    .header("X-HF-ClientId", clientId)
                    .header("X-HF-Timestamp", timestamp)
                    .retrieve().bodyToMono(String.class)
                    .timeout(Duration.ofMillis(properties.timeoutMs())).block();
            return objectMapper.readTree(response == null ? "{}" : response);
        } catch (ToolExecutionException error) {
            throw error;
        } catch (Exception error) {
            throw new ToolExecutionException("MUSIC_UPSTREAM_FAILED",
                    error.getMessage(), true, "音乐服务暂时不可用，请稍后再试。");
        }
    }

    private URI uri(Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(properties.gateway());
        params.forEach(builder::queryParam);
        return builder.build().encode().toUri();
    }

    private void requireConfigured() {
        if (properties.appId().isBlank() || properties.serverCode().isBlank()) {
            throw new ToolExecutionException("MUSIC_NOT_CONFIGURED",
                    "HIFIVE_APP_ID or HIFIVE_SERVER_CODE is missing", false,
                    "音乐服务尚未配置。");
        }
    }

    private void ensureSuccess(JsonNode root, String action) {
        if (root.path("code").asInt() != 10200) {
            throw new ToolExecutionException("MUSIC_UPSTREAM_FAILED",
                    action + " failed: " + root.path("msg").asText(), true,
                    "音乐服务暂时不可用，请稍后再试。");
        }
    }

    private String artist(JsonNode record) {
        for (String field : List.of("artist", "composer", "author")) {
            JsonNode values = record.path(field);
            if (values.isArray() && !values.isEmpty()) {
                String name = values.get(0).path("name").asText("");
                if (!name.isBlank()) {
                    return name;
                }
            }
        }
        return "未知艺人";
    }

    private String nonce() {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder value = new StringBuilder(32);
        for (int index = 0; index < 32; index++) {
            value.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return value.toString();
    }
}
