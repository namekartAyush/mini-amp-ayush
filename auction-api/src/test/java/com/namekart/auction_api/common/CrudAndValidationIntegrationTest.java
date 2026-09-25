package com.namekart.auction_api.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.namekart.auction_api.auction.dto.CreateAuctionRequest;
import com.namekart.auction_api.auction.model.AuctionStatus;
import com.namekart.auction_api.auction.repository.AuctionRepository;
import com.namekart.auction_api.domain.dto.CreateDomainRequest;
import com.namekart.auction_api.domain.model.Domain;
import com.namekart.auction_api.domain.model.DomainStatus;
import com.namekart.auction_api.domain.repository.DomainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CrudAndValidationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DomainRepository domainRepository;

    @Autowired
    private AuctionRepository auctionRepository;

    @BeforeEach
    void setUp() {
        auctionRepository.deleteAll();
        domainRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /api/domains with invalid body returns 400 ProblemDetail with field-level validation errors")
    void testCreateDomainInvalidInputReturns400ProblemDetail() throws Exception {
        // Invalid request: blank name and negative estimated value
        CreateDomainRequest invalidRequest = new CreateDomainRequest(
                "",
                new BigDecimal("-50.00"),
                DomainStatus.AVAILABLE
        );

        mockMvc.perform(post("/api/domains")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.type").value("https://api.miniamp.com/errors/validation-error"))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.estimatedValue").exists());
    }

    @Test
    @DisplayName("GET /api/domains/{id} for non-existent domain returns 404 ProblemDetail")
    void testGetDomainNotFoundReturns404ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/domains/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.type").value("https://api.miniamp.com/errors/not-found"))
                .andExpect(jsonPath("$.resource").value("Domain"))
                .andExpect(jsonPath("$.identifier").value(999999));
    }

    @Test
    @DisplayName("Domain CRUD operations: create, get, page, update, delete")
    void testDomainCrudFlow() throws Exception {
        CreateDomainRequest createRequest = new CreateDomainRequest(
                "quantumcloud.io",
                new BigDecimal("4500.00"),
                DomainStatus.AVAILABLE
        );

        String responseBody = mockMvc.perform(post("/api/domains")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("quantumcloud.io"))
                .andExpect(jsonPath("$.tld").value("io"))
                .andReturn().getResponse().getContentAsString();

        Number domainId = objectMapper.readTree(responseBody).get("id").numberValue();

        // Paginated list
        mockMvc.perform(get("/api/domains?page=0&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalElements").value(1));

        // Delete domain
        mockMvc.perform(delete("/api/domains/" + domainId))
                .andExpect(status().isNoContent());

        // Subsequent get returns 404
        mockMvc.perform(get("/api/domains/" + domainId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/auctions with invalid input returns 400 ProblemDetail")
    void testCreateAuctionInvalidInputReturns400() throws Exception {
        CreateAuctionRequest invalid = new CreateAuctionRequest(
                null,
                new BigDecimal("-10"),
                null,
                null,
                Instant.now().minus(1, ChronoUnit.DAYS),
                null
        );

        mockMvc.perform(post("/api/auctions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.errors.domainId").exists())
                .andExpect(jsonPath("$.errors.startingPrice").exists())
                .andExpect(jsonPath("$.errors.endTime").exists());
    }

    @Test
    @DisplayName("GET /api/auctions/{id} for non-existent auction returns 404 ProblemDetail")
    void testGetAuctionNotFoundReturns404ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/auctions/888888"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.resource").value("Auction"))
                .andExpect(jsonPath("$.identifier").value(888888));
    }

    @Test
    @DisplayName("Auction CRUD flow: create auction, query, page, delete")
    void testAuctionCrudFlow() throws Exception {
        Domain domain = domainRepository.save(new Domain("fintechai.com", "com", new BigDecimal("7000.00"), DomainStatus.AVAILABLE));

        CreateAuctionRequest createAuction = new CreateAuctionRequest(
                domain.getId(),
                new BigDecimal("500.00"),
                new BigDecimal("1000.00"),
                Instant.now(),
                Instant.now().plus(7, ChronoUnit.DAYS),
                AuctionStatus.PENDING
        );

        String responseBody = mockMvc.perform(post("/api/auctions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createAuction)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.domainId").value(domain.getId()))
                .andExpect(jsonPath("$.domainName").value("fintechai.com"))
                .andExpect(jsonPath("$.startingPrice").value(500.00))
                .andReturn().getResponse().getContentAsString();

        Number auctionId = objectMapper.readTree(responseBody).get("id").numberValue();

        // Paginated list
        mockMvc.perform(get("/api/auctions?page=0&size=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));

        // Delete auction
        mockMvc.perform(delete("/api/auctions/" + auctionId))
                .andExpect(status().isNoContent());

        // Subsequent get returns 404
        mockMvc.perform(get("/api/auctions/" + auctionId))
                .andExpect(status().isNotFound());
    }
}
