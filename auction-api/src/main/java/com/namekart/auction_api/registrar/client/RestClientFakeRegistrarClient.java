package com.namekart.auction_api.registrar.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.namekart.auction_api.registrar.dto.FakeRegistrarBidRequest;
import com.namekart.auction_api.registrar.dto.FakeRegistrarBidResponse;
import com.namekart.auction_api.registrar.dto.FakeRegistrarCheckDto;
import com.namekart.auction_api.registrar.dto.FakeRegistrarDomainDto;
import com.namekart.auction_api.registrar.exception.RegistrarBlockedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpServerErrorException;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Modern Spring 6 / Spring Boot 3 implementation of FakeRegistrarClient using RestClient.
 * Features:
 * - Explicit connect and read timeouts (2s connect, 3s read).
 * - Detection of external anti-bot "blocked" responses arriving as HTTP 200 OK.
 * - Idempotent read retries with exponential backoff on transient network / 5xx errors.
 * - Non-idempotent mutations (POST bids) NEVER retried.
 */
@Component
@Primary
public class RestClientFakeRegistrarClient implements FakeRegistrarClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientFakeRegistrarClient.class);

    private RestClient restClient;
    private final ObjectMapper objectMapper;
    private String baseUrl;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public RestClientFakeRegistrarClient(
            @Value("${registrar.fake.base-url:http://localhost:8080}") String baseUrl,
            @Value("${registrar.fake.connect-timeout:2s}") Duration connectTimeout,
            @Value("${registrar.fake.read-timeout:3s}") Duration readTimeout,
            ObjectMapper objectMapper) {

        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;

        initClient(baseUrl);
    }

    public synchronized void setBaseUrl(String newBaseUrl) {
        this.baseUrl = newBaseUrl;
        initClient(newBaseUrl);
    }

    private void initClient(String url) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        this.restClient = RestClient.builder()
                .baseUrl(url)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("Initialized RestClientFakeRegistrarClient with baseUrl={}, connectTimeout={}, readTimeout={}",
                url, connectTimeout, readTimeout);
    }

    @Override
    public String getClientType() {
        return "RestClient";
    }

    @Override
    public List<FakeRegistrarDomainDto> fetchAuctions(Map<String, String> queryParams) {
        return executeWithRetry("fetchAuctions", () -> {
            String uri = buildUri("/api/fake-registrar/domains", queryParams);
            byte[] responseBytes = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        throw new HttpServerErrorException(resp.getStatusCode(), "Registrar server error: " + resp.getStatusText());
                    })
                    .body(byte[].class);

            checkForBlockedResponse(responseBytes);

            if (responseBytes == null || responseBytes.length == 0) {
                return Collections.emptyList();
            }

            return objectMapper.readValue(responseBytes, new TypeReference<List<FakeRegistrarDomainDto>>() {});
        }, 3, 100);
    }

    @Override
    public FakeRegistrarCheckDto checkAvailability(String domain, Map<String, String> queryParams) {
        return executeWithRetry("checkAvailability", () -> {
            String uri = buildUri("/api/fake-registrar/check?domain=" + domain, queryParams);
            byte[] responseBytes = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        throw new HttpServerErrorException(resp.getStatusCode(), "Registrar check error: " + resp.getStatusText());
                    })
                    .body(byte[].class);

            checkForBlockedResponse(responseBytes);

            return objectMapper.readValue(responseBytes, FakeRegistrarCheckDto.class);
        }, 3, 100);
    }

    @Override
    public FakeRegistrarBidResponse placeExternalBid(FakeRegistrarBidRequest request, Integer simulateError) {
        // NON-IDEMPOTENT MUTATION: Execute ONCE without any retry loop!
        log.info("Placing external non-idempotent bid for domain: {}, amount: {}", request.domain(), request.amount());
        String uri = "/api/fake-registrar/bids" + (simulateError != null ? "?simulateError=" + simulateError : "");

        byte[] responseBytes = restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(byte[].class);

        checkForBlockedResponse(responseBytes);

        try {
            return objectMapper.readValue(responseBytes, FakeRegistrarBidResponse.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse bid response", e);
        }
    }

    /**
     * Inspects raw HTTP 200 JSON payload to detect anti-bot/firewall blocked responses.
     */
    private void checkForBlockedResponse(byte[] responseBytes) {
        if (responseBytes == null || responseBytes.length == 0) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBytes);
            if (root.isObject()) {
                JsonNode codeNode = root.get("code");
                JsonNode statusNode = root.get("status");
                JsonNode messageNode = root.get("message");

                boolean isBlockedCode = codeNode != null && "BLOCKED".equalsIgnoreCase(codeNode.asText());
                boolean isBlockedStatus = statusNode != null && "blocked".equalsIgnoreCase(statusNode.asText());
                boolean isBlockedMessage = messageNode != null && messageNode.asText().toLowerCase().contains("blocked");

                if (isBlockedCode || isBlockedStatus || isBlockedMessage) {
                    String msg = messageNode != null ? messageNode.asText() : "Request blocked by registrar anti-bot firewall";
                    log.error("DETECTED HTTP 200 BLOCKED RESPONSE from registrar: {}", msg);
                    throw new RegistrarBlockedException(msg);
                }
            }
        } catch (RegistrarBlockedException rbe) {
            throw rbe;
        } catch (Exception e) {
            // Not a blocked json object, proceed with standard deserialization
        }
    }

    /**
     * Executes safe, idempotent read operations with exponential backoff retry.
     */
    private <T> T executeWithRetry(String operationName, Callable<T> action, int maxAttempts, long initialBackoffMs) {
        int attempt = 0;
        long backoff = initialBackoffMs;
        Exception lastException = null;

        while (attempt < maxAttempts) {
            attempt++;
            try {
                return action.call();
            } catch (RegistrarBlockedException e) {
                // Blocked responses are permanent firewall blocks/rate limits: DO NOT retry immediately
                log.warn("Operation '{}' blocked by registrar. Aborting retry.", operationName);
                throw e;
            } catch (HttpServerErrorException | ResourceAccessException e) {
                lastException = e;
                log.warn("Attempt {}/{} for '{}' failed with transient error: {}. Backing off {}ms",
                        attempt, maxAttempts, operationName, e.getMessage(), backoff);

                if (attempt >= maxAttempts) {
                    break;
                }

                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
                backoff *= 2; // Exponential backoff
            } catch (Exception e) {
                throw new RuntimeException("Unexpected error during " + operationName, e);
            }
        }

        throw new RuntimeException("Exhausted " + maxAttempts + " retry attempts for " + operationName, lastException);
    }

    private String buildUri(String path, Map<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return path;
        }
        StringBuilder sb = new StringBuilder(path);
        boolean first = !path.contains("?");
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            sb.append(first ? "?" : "&");
            first = false;
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }
}
