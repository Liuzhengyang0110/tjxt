package com.tianji.aigc.decision.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "tj.ai.jev")
public class JevProperties {
    private boolean enabled = false;
    private String baseUrl = "https://api.typesafe.ai";
    private String apiKey;
    private String model = "jev-1.13.0";
    private Duration connectTimeout = Duration.ofMillis(500);
    private Duration readTimeout = Duration.ofMillis(1200);
    private int maxContextChars = 4000;
    private int recentMessageLimit = 8;
    private Proxy proxy = new Proxy();

    @Getter
    @Setter
    public static class Proxy {
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 7890;
    }
}
