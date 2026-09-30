package com.tianji.aigc.decision.client;

import cn.hutool.core.util.StrUtil;
import com.tianji.aigc.decision.client.dto.JevChoiceQuestion;
import com.tianji.aigc.decision.client.dto.JevSystemOneRequest;
import com.tianji.aigc.decision.client.dto.JevSystemOneResponse;
import com.tianji.aigc.decision.config.JevProperties;
import com.tianji.aigc.decision.context.RoutingState;
import com.tianji.aigc.decision.exception.JevClientException;
import com.tianji.aigc.decision.model.RouteFallbackReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Component
@RequiredArgsConstructor
public class JevDecisionClient {
    private final RestClient jevRestClient;
    private final JevProperties properties;

    public JevSystemOneResponse evaluate(RoutingState state) {
        if (!properties.isEnabled()) {
            throw new JevClientException(RouteFallbackReason.JEV_DISABLED, "Jev is disabled");
        }
        if (StrUtil.isBlank(properties.getApiKey())) {
            throw new JevClientException(RouteFallbackReason.AUTH_FAILURE, "Jev API key is missing");
        }

        var request = new JevSystemOneRequest(
                state,
                properties.getModel(),
                Map.of("route_agent", JevChoiceQuestion.routeQuestion())
        );

        try {
            JevSystemOneResponse response = jevRestClient.post()
                    .uri("/v1/systemone")
                    .body(request)
                    .retrieve()
                    .body(JevSystemOneResponse.class);
            if (response == null) {
                throw new JevClientException(RouteFallbackReason.INVALID_RESPONSE, "Jev returned an empty response");
            }
            return response;
        } catch (RestClientResponseException e) {
            throw mapStatusException(e);
        } catch (ResourceAccessException e) {
            throw mapResourceException(e);
        } catch (RestClientException e) {
            throw new JevClientException(RouteFallbackReason.NETWORK_ERROR, "Jev request failed", e);
        }
    }

    private JevClientException mapStatusException(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        RouteFallbackReason reason = switch (status) {
            case 401, 403 -> RouteFallbackReason.AUTH_FAILURE;
            case 422 -> RouteFallbackReason.INVALID_REQUEST;
            case 429 -> RouteFallbackReason.RATE_LIMITED;
            case 529 -> RouteFallbackReason.OVERLOADED;
            default -> status >= 500 && status < 600
                    ? RouteFallbackReason.SERVER_ERROR
                    : RouteFallbackReason.UNKNOWN_ERROR;
        };
        return new JevClientException(reason, "Jev returned HTTP " + status, e);
    }

    private JevClientException mapResourceException(ResourceAccessException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof HttpTimeoutException
                    || cause instanceof TimeoutException) {
                return new JevClientException(RouteFallbackReason.TIMEOUT, "Jev request timed out", e);
            }
        }
        return new JevClientException(RouteFallbackReason.NETWORK_ERROR, "Jev network request failed", e);
    }
}
