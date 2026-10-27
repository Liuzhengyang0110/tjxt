package com.tianji.aigc.decision.config;

import cn.hutool.core.util.StrUtil;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties({JevProperties.class, RouteProperties.class})
public class JevClientConfig {

    @Bean
    public RestClient jevRestClient(JevProperties properties) {
        var httpClientBuilder = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout());
        var proxy = properties.getProxy();
        if (proxy.isEnabled()) {
            Assert.hasText(proxy.getHost(), "tj.ai.jev.proxy.host must not be blank when proxy is enabled");
            Assert.isTrue(proxy.getPort() >= 1 && proxy.getPort() <= 65535,
                    "tj.ai.jev.proxy.port must be between 1 and 65535");
            // Scope the HTTP/HTTPS proxy to Jev; do not change JVM-wide networking.
            httpClientBuilder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        }
        var httpClient = httpClientBuilder.build();

        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());

        var builder = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json");

        if (StrUtil.isNotBlank(properties.getApiKey())) {
            builder.defaultHeader(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + properties.getApiKey()
            );
        }
        return builder.build();
    }
}
