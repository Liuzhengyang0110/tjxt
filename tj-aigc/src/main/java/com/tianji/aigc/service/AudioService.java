package com.tianji.aigc.service;

import com.tianji.aigc.vo.AsrVO;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

public interface AudioService {

    /**
     * 文字转语音（TTS）
     *
     * @param text 待合成的文本内容
     * @return 异步响应输出
     */
    ResponseBodyEmitter ttsStream(String text);

    /**
     * 语音转文本（ASR）
     *
     * @param file 待识别的音频文件
     * @return 识别结果
     */
    AsrVO asr(MultipartFile file);

    /**
     * 语音转文字（STT）简化返回。
     *
     * @param file 待识别的音频文件
     * @return 繁体转换为简体后的识别文本
     */
    String stt(MultipartFile file);

}
