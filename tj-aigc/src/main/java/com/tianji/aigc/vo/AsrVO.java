package com.tianji.aigc.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AsrVO {

    /**
     * 完整识别文本。
     */
    private String text;

    /**
     * DashScope 请求 ID，便于排查调用问题。
     */
    private String requestId;

    /**
     * DashScope 返回的计费音频时长，单位为秒。
     */
    private Long durationSeconds;
}
