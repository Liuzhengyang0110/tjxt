package com.tianji.aigc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "tj.ai.dashscope")
public class DashScopeAudioProperties {

    /**
     * 阿里云百炼 API Key。
     */
    private String key;

    /**
     * DashScope 实时语音服务地址。
     */
    private String websocketUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";

    private Tts tts = new Tts();

    private Asr asr = new Asr();

    @Data
    public static class Tts {

        private String model = "qwen-audio-3.0-tts-flash";

        private String voice = "longanhuan_v3.6";

        private String responseFormat = "mp3";

        private Float speed = 1.0F;

        private Long timeoutMs = 120_000L;
    }

    @Data
    public static class Asr {

        /**
         * Qwen-Audio-3.0-ASR-Flash 同步识别接口。
         */
        private String url = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

        private String model = "qwen-audio-3.0-asr-flash";

        /**
         * 文件名和 Content-Type 都无法判断格式时使用的兜底格式。
         */
        private String format = "wav";

        /**
         * 可选。压缩音频通常无需配置，由服务端从文件中识别采样率。
         */
        private Integer sampleRate;

        private List<String> languageHints = List.of("zh");

        /**
         * Base64 Data URI 最大为 10MB，因此原文件默认限制为 7MB。
         */
        private DataSize maxFileSize = DataSize.ofMegabytes(7);

        private Long timeoutMs = 120_000L;
    }
}
