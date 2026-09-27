package com.namekart.auction_api.registrar.client;

import com.namekart.auction_api.registrar.dto.FakeRegistrarBidRequest;
import com.namekart.auction_api.registrar.dto.FakeRegistrarBidResponse;
import com.namekart.auction_api.registrar.dto.FakeRegistrarCheckDto;
import com.namekart.auction_api.registrar.dto.FakeRegistrarDomainDto;

import java.util.List;
import java.util.Map;

/**
 * Contract for talking to external registrar APIs.
 */
public interface FakeRegistrarClient {

    /**
     * Safe, idempotent read to fetch all active domain auctions.
     * Subject to retries with exponential backoff on transient errors.
     */
    List<FakeRegistrarDomainDto> fetchAuctions(Map<String, String> queryParams);

    /**
     * Safe, idempotent read to check a domain's availability.
     * Subject to retries with exponential backoff on transient errors.
     */
    FakeRegistrarCheckDto checkAvailability(String domain, Map<String, String> queryParams);

    /**
     * Non-idempotent mutation to place a bid on the external registrar.
     * MUST NOT be automatically retried on failure to avoid duplicate bids or double charges!
     */
    FakeRegistrarBidResponse placeExternalBid(FakeRegistrarBidRequest request, Integer simulateError);

    /**
     * Client implementation name (e.g. "RestClient" or "Feign").
     */
    String getClientType();
}
