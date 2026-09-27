package com.namekart.auction_api.slice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.namekart.auction_api.auction.controller.AuctionController;
import com.namekart.auction_api.auction.dto.AuctionResponse;
import com.namekart.auction_api.auction.dto.CreateAuctionRequest;
import com.namekart.auction_api.auction.dto.UpdateAuctionRequest;
import com.namekart.auction_api.auction.model.AuctionStatus;
import com.namekart.auction_api.auction.service.AuctionService;
import com.namekart.auction_api.common.exception.GlobalExceptionHandler;
import com.namekart.auction_api.common.exception.ResourceNotFoundException;
import com.namekart.auction_api.common.filter.TraceIdFilter;
import com.namekart.auction_api.security.config.SecurityConfig;
import com.namekart.auction_api.security.filter.JwtAuthenticationFilter;
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
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuctionController.class)
@Import({
        SecurityConfig.class,
        JwtService.class,
        JwtAuthenticationFilter.class,
        TraceIdFilter.class,
        GlobalExceptionHandler.class,
        AuctionControllerSliceTest.SliceTestConfig.class
})
@ActiveProfiles("test")
public class AuctionControllerSliceTest {

    @TestConfiguration
    static class SliceTestConfig {
        @Bean
        public AuctionService auctionService() {
            return new AuctionService(null, null) {
                @Override
                public AuctionResponse getAuctionById(Long id) {
                    if (id.equals(999L)) {
                        throw new ResourceNotFoundException("Auction", id);
                    }
                    return new AuctionResponse(
                            id,
                            1L,
                            "slice-test.com",
                            new BigDecimal("100.00"),
                            new BigDecimal("500.00"),
                            new BigDecimal("150.00"),
                            AuctionStatus.ACTIVE,
                            Instant.now().minus(1, ChronoUnit.HOURS),
                            Instant.now().plus(24, ChronoUnit.HOURS),
                            Instant.now().minus(2, ChronoUnit.HOURS),
                            Instant.now()
                    );
                }

                @Override
                public Page<AuctionResponse> listAuctions(AuctionStatus status, Pageable pageable) {
                    AuctionResponse res = new AuctionResponse(
                            1L, 1L, "demo.io",
                            new BigDecimal("50.00"), new BigDecimal("100.00"), new BigDecimal("75.00"),
                            AuctionStatus.ACTIVE,
                            Instant.now().minus(1, ChronoUnit.HOURS),
                            Instant.now().plus(24, ChronoUnit.HOURS),
                            Instant.now(), Instant.now()
                    );
                    return new PageImpl<>(List.of(res), pageable, 1);
                }

                @Override
                public AuctionResponse createAuction(CreateAuctionRequest request) {
                    return new AuctionResponse(
                            42L,
                            request.domainId(),
                            "new-domain.org",
                            request.startingPrice(),
                            request.reservePrice(),
                            null,
                            AuctionStatus.ACTIVE,
                            request.startTime(),
                            request.endTime(),
                            Instant.now(),
                            Instant.now()
                    );
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Slice: GET /api/auctions/{id} with existing ID returns 200 OK")
    void testGetAuctionByIdFound() throws Exception {
        mockMvc.perform(get("/api/auctions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.domainName").value("slice-test.com"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currentHighestBid").value(150.00));
    }

    @Test
    @DisplayName("Slice: GET /api/auctions/{id} with non-existent ID returns 404 ProblemDetail")
    void testGetAuctionByIdNotFound() throws Exception {
        mockMvc.perform(get("/api/auctions/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.resource").value("Auction"))
                .andExpect(jsonPath("$.identifier").value("999"));
    }

    @Test
    @DisplayName("Slice: GET /api/auctions returns 200 OK with paginated content")
    void testListAuctions() throws Exception {
        mockMvc.perform(get("/api/auctions?page=0&size=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].domainName").value("demo.io"));
    }

    @Test
    @DisplayName("Slice: POST /api/auctions with valid request returns 201 Created")
    void testCreateAuctionSuccess() throws Exception {
        CreateAuctionRequest request = new CreateAuctionRequest(
                1L,
                new BigDecimal("100.00"),
                new BigDecimal("300.00"),
                Instant.now(),
                Instant.now().plus(48, ChronoUnit.HOURS),
                AuctionStatus.ACTIVE
        );

        mockMvc.perform(post("/api/auctions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.startingPrice").value(100.00));
    }

    @Test
    @DisplayName("Slice: POST /api/auctions with invalid/past end time returns 400 Bad Request")
    void testCreateAuctionInvalidEndTime() throws Exception {
        // Past end time triggers @Future validation failure
        CreateAuctionRequest request = new CreateAuctionRequest(
                1L,
                new BigDecimal("100.00"),
                new BigDecimal("300.00"),
                Instant.now().minus(2, ChronoUnit.HOURS),
                Instant.now().minus(1, ChronoUnit.HOURS), // In the past!
                AuctionStatus.ACTIVE
        );

        mockMvc.perform(post("/api/auctions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.errors.endTime").exists());
    }
}
