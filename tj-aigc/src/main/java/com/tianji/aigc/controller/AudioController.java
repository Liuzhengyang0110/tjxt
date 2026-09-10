package com.tianji.aigc.controller;

import com.tianji.aigc.service.AudioService;
import com.tianji.aigc.vo.AsrVO;
import com.tianji.common.annotations.NoWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

@RestController
@RequestMapping("/audio")
@RequiredArgsConstructor
public class AudioController {

    private final AudioService audioService;

    @NoWrapper
    @PostMapping(value = "/tts-stream", produces = "audio/mpeg")
    public ResponseBodyEmitter ttsStream(@RequestBody String text) {
        return this.audioService.ttsStream(text);
    }

    @PostMapping(value = "/asr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AsrVO asr(@RequestPart("audioFile") MultipartFile file) {
        return this.audioService.asr(file);
    }

    /**
     * 语音转文字简化接口，与课程中的 STT 接口命名保持一致。
     */
    @PostMapping(value = "/stt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String stt(@RequestPart("audioFile") MultipartFile file) {
        return this.audioService.stt(file);
    }
}
