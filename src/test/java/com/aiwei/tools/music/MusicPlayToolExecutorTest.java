package com.aiwei.tools.music;

import com.aiwei.tools.contract.ToolContext;
import com.aiwei.tools.contract.ToolExecutionResult;
import com.aiwei.tools.contract.ToolInvokeRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 本地音乐点播回归测试。
 */
class MusicPlayToolExecutorTest {

    private final MusicPlayToolExecutor executor = new MusicPlayToolExecutor(
            mock(HifiveClient.class),
            new LocalMusicLibrary(new LocalMusicProperties(true, "http://tools.test")));

    @Test
    void playsNamedLocalTrackWithoutCallingRemoteProvider() {
        ToolExecutionResult result = executor.execute(request(Map.of(
                "action", "play", "query", "播放夏日信号", "count", 3)));

        assertThat(result.provider()).isEqualTo("local");
        assertThat(result.data()).containsEntry("matched", true);
        assertThat(track(result))
                .containsEntry("name", "夏日信号")
                .containsEntry("audio_url", "http://tools.test/music/summer-pop.wav");
    }

    @Test
    void fallsBackToRandomLocalTrackWhenNameIsMissing() {
        ToolExecutionResult result = executor.execute(request(Map.of(
                "action", "play", "query", "不存在的歌")));

        assertThat(result.data()).containsEntry("matched", false);
        assertThat(result.summary()).contains("没有找到《不存在的歌》").contains("随机播放");
        assertThat(track(result)).containsKey("audio_url");
    }

    @Test
    void returnsStopActionWithoutSelectingMusic() {
        ToolExecutionResult result = executor.execute(request(Map.of("action", "stop")));

        assertThat(result.data()).containsEntry("action", "stop");
        assertThat(result.summary()).isEqualTo("已停止播放。");
    }

    @Test
    void returnsPauseActionWithoutSelectingAnotherTrack() {
        ToolExecutionResult result = executor.execute(request(Map.of("action", "pause")));

        assertThat(result.data()).containsEntry("action", "pause");
        assertThat(result.data()).doesNotContainKeys("track", "queue");
        assertThat(result.summary()).isEqualTo("已暂停播放。");
    }

    private ToolInvokeRequest request(Map<String, Object> arguments) {
        return new ToolInvokeRequest("request", "default", "user", "session",
                arguments, ToolContext.empty(), null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> track(ToolExecutionResult result) {
        return (Map<String, Object>) result.data().get("track");
    }
}
