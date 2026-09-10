package com.tianji.aigc.service.impl;

import com.alibaba.dashscope.audio.tts.SpeechSynthesisResult;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.utils.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.tianji.aigc.config.DashScopeAudioProperties;
import com.tianji.aigc.service.AudioService;
import com.tianji.aigc.vo.AsrVO;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.AssertUtils;
import io.reactivex.disposables.Disposable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashScopeAudioServiceImpl implements AudioService {

    private static final MediaType AUDIO_MPEG = MediaType.parseMediaType("audio/mpeg");
    private static final Set<String> SUPPORTED_ASR_FORMATS = Set.of(
            "aac", "amr", "avi", "flac", "flv", "m4a", "mkv", "mov", "mp3",
            "mp4", "mpeg", "ogg", "opus", "wav", "webm", "wma", "wmv"
    );

    private final DashScopeAudioProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public ResponseBodyEmitter ttsStream(String requestBody) {
        String text = extractText(requestBody);
        AssertUtils.isNotBlank(text, "待合成文本不能为空");
        AssertUtils.isNotBlank(properties.getKey(), "DashScope API Key 未配置");

        DashScopeAudioProperties.Tts tts = properties.getTts();
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(tts.getTimeoutMs());
        SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                .apiKey(properties.getKey())
                .model(tts.getModel())
                .voice(tts.getVoice())
                .format(resolveAudioFormat(tts.getResponseFormat()))
                .speechRate(tts.getSpeed())
                .build();

        // DashScope SDK 使用全局地址配置。每次调用前设置，确保 Nacos 中的地址生效。
        Constants.baseWebsocketApiUrl = properties.getWebsocketUrl();
        SpeechSynthesizer synthesizer = new SpeechSynthesizer(param, null);
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicReference<Disposable> subscription = new AtomicReference<>();

        log.info("开始调用 DashScope 进行语音合成，model={}, voice={}, textLength={}",
                tts.getModel(), tts.getVoice(), text.length());

        try {
            Disposable disposable = synthesizer.callAsFlowable(text).subscribe(
                    result -> sendAudioFrame(emitter, result),
                    error -> {
                        log.error("DashScope 语音合成失败，requestId={}", synthesizer.getLastRequestId(), error);
                        closeSynthesizer(synthesizer, closed);
                        emitter.completeWithError(error);
                    },
                    () -> {
                        log.info("DashScope 语音合成完成，requestId={}, firstPackageDelay={}ms",
                                synthesizer.getLastRequestId(), synthesizer.getFirstPackageDelay());
                        closeSynthesizer(synthesizer, closed);
                        emitter.complete();
                    }
            );
            subscription.set(disposable);
        } catch (Exception e) {
            closeSynthesizer(synthesizer, closed);
            emitter.completeWithError(e);
            return emitter;
        }

        Runnable cleanup = () -> {
            Disposable disposable = subscription.get();
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
            }
            closeSynthesizer(synthesizer, closed);
        };
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        emitter.onCompletion(cleanup);
        return emitter;
    }

    @Override
    public AsrVO asr(MultipartFile file) {
        AssertUtils.isNotNull(file, "音频文件不能为空");
        AssertUtils.isTrue(!file.isEmpty(), "音频文件不能为空");
        AssertUtils.isNotBlank(properties.getKey(), "DashScope API Key 未配置");

        DashScopeAudioProperties.Asr asr = properties.getAsr();
        AssertUtils.isNotBlank(asr.getUrl(), "DashScope ASR 接口地址未配置");
        AssertUtils.isNotBlank(asr.getModel(), "DashScope ASR 模型未配置");
        AssertUtils.isTrue(file.getSize() <= asr.getMaxFileSize().toBytes(),
                "音频文件不能超过 " + asr.getMaxFileSize());

        String format = resolveAsrFormat(file, asr.getFormat());
        String mimeType = resolveMimeType(file.getContentType(), format);
        byte[] audioBytes;
        try {
            audioBytes = file.getBytes();
        } catch (IOException e) {
            throw new BizIllegalException(500, "读取音频文件失败", e);
        }

        String dataUri = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(audioBytes);
        Map<String, Object> parameters = buildAsrParameters(asr, format);
        Map<String, Object> requestBody = Map.of(
                "model", asr.getModel(),
                "input", Map.of(
                        "messages", List.of(Map.of(
                                "role", "user",
                                "content", List.of(Map.of(
                                        "type", "input_audio",
                                        "input_audio", Map.of("data", dataUri)
                                ))
                        ))
                ),
                "parameters", parameters
        );

        log.info("开始调用 DashScope 进行语音识别，model={}, format={}, fileSize={}",
                asr.getModel(), format, file.getSize());
        JsonNode response = callAsr(asr, requestBody);
        AsrVO result = parseAsrResponse(response);
        log.info("DashScope 语音识别完成，requestId={}, duration={}s",
                result.getRequestId(), result.getDurationSeconds());
        return result;
    }

    @Override
    public String stt(MultipartFile file) {
        return asr(file).getText();
    }

    private JsonNode callAsr(DashScopeAudioProperties.Asr asr, Map<String, Object> requestBody) {
        try {
            String json = objectMapper.writeValueAsString(requestBody);
            HttpRequest request = HttpRequest.newBuilder(URI.create(asr.getUrl()))
                    .timeout(Duration.ofMillis(asr.getTimeoutMs()))
                    .header("Authorization", "Bearer " + properties.getKey())
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("X-DashScope-SSE", "disable")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String error = extractProviderError(response.body());
                log.error("DashScope 语音识别失败，status={}, message={}", response.statusCode(), error);
                throw new BizIllegalException("语音识别服务调用失败：" + error);
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizIllegalException(500, "语音识别请求被中断", e);
        } catch (IOException | IllegalArgumentException e) {
            throw new BizIllegalException(500, "语音识别服务调用失败", e);
        }
    }

    private Map<String, Object> buildAsrParameters(DashScopeAudioProperties.Asr asr, String format) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("format", format);
        if (asr.getSampleRate() != null) {
            parameters.put("sample_rate", String.valueOf(asr.getSampleRate()));
        }
        if (asr.getLanguageHints() != null && !asr.getLanguageHints().isEmpty()) {
            parameters.put("language_hints", asr.getLanguageHints());
        }
        return parameters;
    }

    private AsrVO parseAsrResponse(JsonNode response) {
        JsonNode output = response.path("output");
        String text = output.path("text").asText();
        if (!StringUtils.hasText(text)) {
            text = output.path("sentence").path("text").asText();
        }
        if (!StringUtils.hasText(text)) {
            text = output.path("output").path("sentence").path("text").asText();
        }
        if (!StringUtils.hasText(text)) {
            throw new BizIllegalException("语音识别服务未返回文本");
        }
        text = ZhConverterUtil.toSimple(text);

        JsonNode durationNode = response.path("usage").path("duration");
        if (durationNode.isMissingNode()) {
            durationNode = output.path("usage").path("duration");
        }
        return AsrVO.builder()
                .text(text)
                .requestId(response.path("request_id").asText(null))
                .durationSeconds(durationNode.isNumber() ? durationNode.asLong() : null)
                .build();
    }

    private String extractProviderError(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return "DashScope 返回空错误信息";
        }
        try {
            JsonNode error = objectMapper.readTree(responseBody);
            String message = error.path("message").asText();
            if (!StringUtils.hasText(message)) {
                message = error.path("output").path("message").asText();
            }
            if (StringUtils.hasText(message)) {
                return message;
            }
        } catch (IOException ignored) {
            // 非 JSON 错误响应按纯文本返回。
        }
        return responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody;
    }

    private String resolveAsrFormat(MultipartFile file, String fallbackFormat) {
        String extension = StringUtils.getFilenameExtension(file.getOriginalFilename());
        String format = extension == null ? null : extension.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_ASR_FORMATS.contains(format)) {
            format = formatFromMimeType(file.getContentType());
        }
        if (!SUPPORTED_ASR_FORMATS.contains(format)) {
            format = fallbackFormat == null ? null : fallbackFormat.toLowerCase(Locale.ROOT);
        }
        AssertUtils.isTrue(SUPPORTED_ASR_FORMATS.contains(format), "不支持的音频格式");
        return format;
    }

    private String formatFromMimeType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return null;
        }
        String mimeType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return switch (mimeType) {
            case "audio/mpeg" -> "mp3";
            case "audio/x-m4a", "audio/mp4" -> "m4a";
            case "audio/x-wav", "audio/wave", "audio/wav" -> "wav";
            case "audio/ogg" -> "ogg";
            case "audio/webm" -> "webm";
            case "audio/flac" -> "flac";
            case "audio/aac" -> "aac";
            case "audio/amr" -> "amr";
            case "audio/opus" -> "opus";
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            default -> null;
        };
    }

    private String resolveMimeType(String contentType, String format) {
        if (StringUtils.hasText(contentType)) {
            String mimeType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (mimeType.startsWith("audio/") || mimeType.startsWith("video/")) {
                return mimeType;
            }
        }
        return switch (format) {
            case "mp3", "mpeg" -> "audio/mpeg";
            case "m4a", "mp4" -> "audio/mp4";
            case "wav" -> "audio/wav";
            case "webm" -> "audio/webm";
            case "ogg", "opus" -> "audio/ogg";
            default -> "audio/" + format;
        };
    }

    private void sendAudioFrame(ResponseBodyEmitter emitter, SpeechSynthesisResult result) {
        ByteBuffer audioFrame = result.getAudioFrame();
        if (audioFrame == null || !audioFrame.hasRemaining()) {
            return;
        }
        byte[] audioBytes = new byte[audioFrame.remaining()];
        audioFrame.get(audioBytes);
        try {
            emitter.send(audioBytes, AUDIO_MPEG);
        } catch (IOException e) {
            emitter.completeWithError(e);
            throw new IllegalStateException("发送语音数据失败", e);
        }
    }

    private String extractText(String requestBody) {
        if (requestBody == null) {
            return null;
        }
        String text = requestBody.trim();
        if (text.isEmpty()) {
            return text;
        }
        try {
            if (text.startsWith("\"") && text.endsWith("\"")) {
                return objectMapper.readValue(text, String.class).trim();
            }
            if (text.startsWith("{")) {
                JsonNode body = objectMapper.readTree(text);
                JsonNode textNode = body.get("text");
                if (textNode != null && textNode.isTextual()) {
                    return textNode.asText().trim();
                }
            }
        } catch (IOException e) {
            log.debug("请求体不是标准 JSON，按普通文本处理", e);
        }
        return text;
    }

    private SpeechSynthesisAudioFormat resolveAudioFormat(String responseFormat) {
        String format = responseFormat == null ? "mp3" : responseFormat.toLowerCase(Locale.ROOT);
        if (!"mp3".equals(format)) {
            log.warn("当前接口响应类型固定为 audio/mpeg，已忽略不支持的音频格式：{}", responseFormat);
        }
        return SpeechSynthesisAudioFormat.MP3_24000HZ_MONO_256KBPS;
    }

    private void closeSynthesizer(SpeechSynthesizer synthesizer, AtomicBoolean closed) {
        if (!closed.compareAndSet(false, true) || synthesizer.getDuplexApi() == null) {
            return;
        }
        try {
            synthesizer.getDuplexApi().close(1000, "bye");
        } catch (Exception e) {
            log.debug("关闭 DashScope 语音连接失败", e);
        }
    }
}
