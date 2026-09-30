package com.tianji.aigc.decision.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record JevUsage(
        @JsonProperty("input_tokens") Long inputTokens,
        @JsonProperty("output_tokens") Long outputTokens
) {
}
