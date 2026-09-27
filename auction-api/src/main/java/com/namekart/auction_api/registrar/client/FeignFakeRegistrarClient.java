package com.namekart.auction_api.registrar.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.namekart.auction_api.registrar.dto.FakeRegistrarBidRequest;
import com.namekart.auction_api.registrar.dto.FakeRegistrarBidResponse;
import com.namekart.auction_api.registrar.dto.FakeRegistrarCheckDto;
import com.namekart.auction_api.registrar.dto.FakeRegistrarDomainDto;
import com.namekart.auction_api.registrar.exception.RegistrarBlockedException;
import feign.Feign;
import feign.FeignException;
import feign.Headers;
import feign.Param;
import feign.Request;
import feign.RequestLine;
import feign.Response;
import feign.Util;
import feign.codec.Decoder;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenFeign implementation of FakeRegistrarClient.
 * Configured with:
 * - Explicit connect (2s) and read (3s) timeouts.
 * - Custom Feign Decoder inspecting HTTP 200 for blocked responses.
 * - Idempotent read retry logic.
 */
@Component("feignFakeRegistrarClient")
public class FeignFakeRegistrarClient implements FakeRegistrarClient {

    private static final Logger log = LoggerFactory.getLogger(FeignFakeRegistrarClient.class);

    public interface FeignRegistrarApi {
        @RequestLine("GET /api/fake-registrar/domains")
        List<FakeRegistrarDomainDto> fetchAuctions();

        @RequestLine("GET /api/fake-registrar/domains?delay={delay}&simulateError={error}&simulateBlocked={blocked}&failFirstN={failFirstN}")
        List<FakeRegistrarDomainDto> fetchAuctionsWithParams(
                @Param("delay") long delay,
                @Param("error") Integer error,
                @Param("blocked") boolean blocked,
                @Param("failFirstN") int failFirstN
        );

        @RequestLine("GET /api/fake-registrar/check?domain={domain}&delay={delay}&simulateBlocked={blocked}")
        FakeRegistrarCheckDto checkAvailability(
                @Param("domain") String domain,
                @Param("delay") long delay,
                @Param("blocked") boolean blocked
        );

        @RequestLine("POST /api/fake-registrar/bids")
        @Headers("Content-Type: application/json")
        FakeRegistrarBidResponse placeExternalBid(FakeRegistrarBidRequest request);

        @RequestLine("POST /api/fake-registrar/bids?simulateError={simulateError}")
        @Headers("Content-Type: application/json")
        FakeRegistrarBidResponse placeExternalBidWithError(
                FakeRegistrarBidRequest request,
                @Param("simulateError") Integer simulateError
        );
    }

    private FeignRegistrarApi api;
    private final ObjectMapper objectMapper;
    private final Request.Options options;
    private final Decoder detectingDecoder;

    public FeignFakeRegistrarClient(
            @Value("${registrar.fake.base-url:http://localhost:8080}") String baseUrl,
            @Value("${registrar.fake.connect-timeout:2s}") Duration connectTimeout,
            @Value("${registrar.fake.read-timeout:3s}") Duration readTimeout,
            ObjectMapper objectMapper) {

        this.objectMapper = objectMapper;

        this.options = new Request.Options(
                connectTimeout.toMillis(), TimeUnit.MILLISECONDS,
                readTimeout.toMillis(), TimeUnit.MILLISECONDS,
                true
        );

        this.detectingDecoder = new Decoder() {
            private final Decoder delegate = new JacksonDecoder(objectMapper);

            @Override
            public Object decode(Response response, Type type) throws IOException, FeignException {
                if (response.body() == null) {
                    return null;
                }
                byte[] bodyBytes = Util.toByteArray(response.body().asInputStream());
                String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);

                try {
                    JsonNode node = objectMapper.readTree(bodyStr);
                    if (node.isObject() && node.has("code") && "BLOCKED".equalsIgnoreCase(node.get("code").asText())) {
                        String msg = node.has("message") ? node.get("message").asText() : "Blocked by registrar";
                        log.error("Feign client detected HTTP 200 BLOCKED payload: {}", msg);
                        throw new RegistrarBlockedException(msg);
                    }
                } catch (RegistrarBlockedException rbe) {
                    throw rbe;
                } catch (Exception ignored) {
                }

                Response rebuilt = response.toBuilder().body(bodyBytes).build();
                return delegate.decode(rebuilt, type);
            }
        };

        setBaseUrl(baseUrl);
    }

    public synchronized void setBaseUrl(String newBaseUrl) {
        this.api = Feign.builder()
                .options(options)
                .encoder(new JacksonEncoder(objectMapper))
                .decoder(detectingDecoder)
                .target(FeignRegistrarApi.class, newBaseUrl);

        log.info("Initialized FeignFakeRegistrarClient with baseUrl={}", newBaseUrl);
    }

    @Override
    public String getClientType() {
        return "Feign";
    }

    @Override
    public List<FakeRegistrarDomainDto> fetchAuctions(Map<String, String> queryParams) {
        long delay = getLongParam(queryParams, "delay", 0);
        Integer error = getIntParam(queryParams, "simulateError");
        boolean blocked = getBoolParam(queryParams, "simulateBlocked", false);
        int failFirstN = (int) getLongParam(queryParams, "failFirstN", 0);

        int maxAttempts = 3;
        int attempt = 0;
        long backoff = 100;
        Exception lastException = null;

        while (attempt < maxAttempts) {
            attempt++;
            try {
                return api.fetchAuctionsWithParams(delay, error, blocked, failFirstN);
            } catch (RegistrarBlockedException rbe) {
                throw rbe;
            } catch (FeignException e) {
                if (e.getCause() instanceof RegistrarBlockedException rbe) {
                    throw rbe;
                }
                lastException = e;
                if (attempt >= maxAttempts) break;
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
                backoff *= 2;
            }
        }
        throw new RuntimeException("Feign exhausted retries for fetchAuctions", lastException);
    }

    @Override
    public FakeRegistrarCheckDto checkAvailability(String domain, Map<String, String> queryParams) {
        long delay = getLongParam(queryParams, "delay", 0);
        boolean blocked = getBoolParam(queryParams, "simulateBlocked", false);

        int maxAttempts = 3;
        int attempt = 0;
        long backoff = 100;
        Exception lastException = null;

        while (attempt < maxAttempts) {
            attempt++;
            try {
                return api.checkAvailability(domain, delay, blocked);
            } catch (RegistrarBlockedException rbe) {
                throw rbe;
            } catch (FeignException e) {
                if (e.getCause() instanceof RegistrarBlockedException rbe) {
                    throw rbe;
                }
                lastException = e;
                if (attempt >= maxAttempts) break;
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
                backoff *= 2;
            }
        }
        throw new RuntimeException("Feign exhausted retries for checkAvailability", lastException);
    }

    @Override
    public FakeRegistrarBidResponse placeExternalBid(FakeRegistrarBidRequest request, Integer simulateError) {
        // NON-IDEMPOTENT: No retry
        if (simulateError != null && simulateError > 0) {
            return api.placeExternalBidWithError(request, simulateError);
        }
        return api.placeExternalBid(request);
    }

    private long getLongParam(Map<String, String> params, String key, long defaultValue) {
        if (params == null || !params.containsKey(key)) return defaultValue;
        try {
            return Long.parseLong(params.get(key));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private Integer getIntParam(Map<String, String> params, String key) {
        if (params == null || !params.containsKey(key)) return null;
        try {
            return Integer.parseInt(params.get(key));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean getBoolParam(Map<String, String> params, String key, boolean defaultValue) {
        if (params == null || !params.containsKey(key)) return defaultValue;
        return Boolean.parseBoolean(params.get(key));
    }
}
