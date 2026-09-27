package com.namekart.auction_api.slice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.namekart.auction_api.bid.controller.BidController;
import com.namekart.auction_api.bid.dto.BidResponse;
import com.namekart.auction_api.bid.dto.PlaceBidRequest;
import com.namekart.auction_api.bid.service.BidService;
import com.namekart.auction_api.common.exception.GlobalExceptionHandler;
import com.namekart.auction_api.common.filter.TraceIdFilter;
import com.namekart.auction_api.security.config.SecurityConfig;
import com.namekart.auction_api.security.filter.JwtAuthenticationFilter;
import com.namekart.auction_api.security.model.UserRole;
import com.namekart.auction_api.security.service.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BidController.class)
@Import({
        SecurityConfig.class,
        JwtService.class,
        JwtAuthenticationFilter.class,
        TraceIdFilter.class,
        GlobalExceptionHandler.class,
        BidControllerSliceTest.SliceTestConfig.class
})
@ActiveProfiles("test")
public class BidControllerSliceTest {

    private static final AtomicReference<PlaceBidRequest> lastCapturedRequest = new AtomicReference<>();

    @TestConfiguration
    static class SliceTestConfig {
        @Bean
        public BidService bidService() {
            return new BidService(null, null, null) {
                @Override
                public BidResponse placeBid(PlaceBidRequest request) {
                    lastCapturedRequest.set(request);
                    return new BidResponse(
                            101L,
                            request.auctionId(),
                            request.bidderEmail(),
                            request.amount(),
                            Instant.now()
                    );
                }

                @Override
                public Page<BidResponse> listBids(String bidderEmail, Pageable pageable) {
                    List<BidResponse> bids = List.of(
                            new BidResponse(1L, 10L, "alice@example.com", new BigDecimal("150.00"), Instant.now()),
                            new BidResponse(2L, 10L, "bob@example.com", new BigDecimal("160.00"), Instant.now())
                    );
                    return new PageImpl<>(bids, pageable, bids.size());
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Test
    @DisplayName("Slice: POST /api/bids with valid payload and ROLE_BIDDER returns 201 Created")
    void testPlaceBidSuccess() throws Exception {
        lastCapturedRequest.set(null);
        String token = jwtService.generateToken("alice-bidder", UserRole.BIDDER);
        PlaceBidRequest request = new PlaceBidRequest(10L, "alice@example.com", new BigDecimal("175.50"));

        mockMvc.perform(post("/api/bids")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(101))
                .andExpect(jsonPath("$.auctionId").value(10))
                .andExpect(jsonPath("$.bidderEmail").value("alice@example.com"))
                .andExpect(jsonPath("$.amount").value(175.50));

        assertThat(lastCapturedRequest.get()).isNotNull();
        assertThat(lastCapturedRequest.get().amount()).isEqualByComparingTo("175.50");
    }

    @Test
    @DisplayName("Slice: POST /api/bids with invalid email returns 400 Bad Request with field errors")
    void testPlaceBidValidationFailureInvalidEmail() throws Exception {
        String token = jwtService.generateToken("bob-bidder", UserRole.BIDDER);
        PlaceBidRequest request = new PlaceBidRequest(10L, "not-an-email", new BigDecimal("100.00"));

        mockMvc.perform(post("/api/bids")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.errors.bidderEmail").exists());
    }

    @Test
    @DisplayName("Slice: POST /api/bids with zero or negative amount returns 400 Bad Request")
    void testPlaceBidValidationFailureNegativeAmount() throws Exception {
        String token = jwtService.generateToken("bob-bidder", UserRole.BIDDER);
        PlaceBidRequest request = new PlaceBidRequest(10L, "bob@example.com", new BigDecimal("-5.00"));

        mockMvc.perform(post("/api/bids")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    @DisplayName("Slice: POST /api/bids with missing body returns 400 Bad Request")
    void testPlaceBidMissingBody() throws Exception {
        String token = jwtService.generateToken("bob-bidder", UserRole.BIDDER);

        mockMvc.perform(post("/api/bids")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Slice: POST /api/bids without authentication token returns 401 Unauthorized ProblemDetail")
    void testPlaceBidUnauthenticated() throws Exception {
        PlaceBidRequest request = new PlaceBidRequest(10L, "alice@example.com", new BigDecimal("100.00"));

        mockMvc.perform(post("/api/bids")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Slice: POST /api/bids with ROLE_VIEWER returns 403 Forbidden ProblemDetail")
    void testPlaceBidForbiddenForViewer() throws Exception {
        String viewerToken = jwtService.generateToken("viewer-user", UserRole.VIEWER);
        PlaceBidRequest request = new PlaceBidRequest(10L, "viewer@example.com", new BigDecimal("100.00"));

        mockMvc.perform(post("/api/bids")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Slice: GET /api/bids returns paginated bid list")
    void testListBidsPaginated() throws Exception {
        mockMvc.perform(get("/api/bids")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].bidderEmail").value("alice@example.com"))
                .andExpect(jsonPath("$.content[1].bidderEmail").value("bob@example.com"));
    }
}
