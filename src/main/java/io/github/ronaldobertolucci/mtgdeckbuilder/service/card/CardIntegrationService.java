package io.github.ronaldobertolucci.mtgdeckbuilder.service.card;

import io.github.ronaldobertolucci.mtgdeckbuilder.config.CardManagerProperties;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardSearchResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.ResolvedCardResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.CardManagerUnavailableException;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.CardNotFoundException;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.*;
import java.time.Duration;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

@Service
public class CardIntegrationService {

    private final Cache<UUID, ResolvedCardResponse> resolvedCards =
            Caffeine.newBuilder().maximumSize(10000)
                    .expireAfterWrite(Duration.ofHours(24)).build();

    private final RestClient restClient;
    private final CardManagerProperties properties;

    public CardIntegrationService(@Qualifier("cardManagerRestClient") RestClient restClient,
            CardManagerProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Cacheable(value = "cards", key = "#oracleId")
    public CardDetailsResponse fetchCardDetails(UUID oracleId) {
        return requestCardDetails(oracleId);
    }

    @org.springframework.cache.annotation.CachePut(value = "cards", key = "#oracleId")
    public CardDetailsResponse refreshCardDetails(UUID oracleId) {
        return requestCardDetails(oracleId);
    }

    @Cacheable(value = "cards_by_name", key = "#name")
    public CardDetailsResponse fetchCardDetailsByName(String name) {
        return requestCardByName(name, false);
    }

    public CardDetailsResponse fetchAccessoryByName(String name) {
        return requestCardByName(name, true);
    }

    private CardDetailsResponse requestCardByName(String name, boolean includeTokens) {
        if (!StringUtils.hasText(properties.url())) {
            throw new CardManagerUnavailableException("Card Manager URL is not configured", null);
        }
        try {
            CardSearchResponse response = restClient.get()
                    .uri("/cards/search?lang=en&name_exact={name}&limit=1" + (includeTokens ? "&include_tokens=true" : ""), name)
                    .retrieve()
                    .body(CardSearchResponse.class);
            if (response == null || response.items() == null) {
                throw new CardManagerUnavailableException("Card Manager returned an invalid search response", null);
            }
            if (response.items().isEmpty()) {
                throw new RuleViolationException("Card not found by name: " + name);
            }
            if (response.items().getFirst() == null) {
                throw new CardManagerUnavailableException("Card Manager returned an invalid search response", null);
            }
            return response.items().getFirst();
        } catch (RestClientException ex) {
            throw new CardManagerUnavailableException("Card Manager is unavailable", ex);
        }
    }

    public List<ResolvedCardResponse> resolveCards(Collection<UUID> ids) {
        var unique = new LinkedHashSet<>(ids);
        if (unique.contains(null)) throw new CardManagerUnavailableException("Related card has no Scryfall ID", null);
        var result = new LinkedHashMap<UUID, ResolvedCardResponse>(resolvedCards.getAllPresent(unique));
        var missing = unique.stream().filter(id -> !result.containsKey(id)).toList();
        // Bound payload sizes even though the current endpoint has no batch limit.
        for (int offset = 0; offset < missing.size(); offset += 100) {
            var batch = missing.subList(offset, Math.min(offset + 100, missing.size()));
            if (!StringUtils.hasText(properties.url())) throw new CardManagerUnavailableException("Card Manager URL is not configured", null);
            try {
                var response = restClient.post().uri("/cards/resolve").body(Map.of("ids", batch))
                        .retrieve().body(new ParameterizedTypeReference<List<ResolvedCardResponse>>() {});
                var validated = new HashMap<UUID, ResolvedCardResponse>();
                if (response == null) throw new CardManagerUnavailableException("Empty card resolution response", null);
                for (var card : response) {
                    if (card == null || card.id() == null || !batch.contains(card.id()) || card.oracleId() == null
                            || card.name() == null || card.layout() == null || card.typeLine() == null
                            || validated.putIfAbsent(card.id(), card) != null)
                        throw new CardManagerUnavailableException("Invalid card resolution response", null);
                }
                if (validated.size() != batch.size()) throw new CardManagerUnavailableException("Incomplete card resolution response", null);
                result.putAll(validated);
            } catch (RestClientException ex) {
                throw new CardManagerUnavailableException("Card Manager resolution is unavailable", ex);
            }
        }
        // Cache only newly fetched entries; reads must not extend their freshness indefinitely.
        missing.forEach(id -> resolvedCards.put(id, result.get(id)));
        return unique.stream().map(result::get).toList();
    }

    private CardDetailsResponse requestCardDetails(UUID oracleId) {
        if (!properties.isOracleLookupConfigured()) {
            throw new CardManagerUnavailableException(
                    "Card Manager oracle lookup is not configured: define services.card-manager.url "
                            + "and services.card-manager.oracle-details-path with {oracleId}",
                    null);
        }
        try {
            CardDetailsResponse card = restClient.get()
                    .uri(properties.oracleDetailsPath(), uriBuilder -> uriBuilder
                            .replaceQueryParam("lang", "en")
                            .build(oracleId))
                    .retrieve()
                    .body(CardDetailsResponse.class);
            if (card == null) {
                throw new CardManagerUnavailableException("Card Manager returned an empty response", null);
            }
            return card;
        } catch (HttpClientErrorException ex) {
            throw new CardNotFoundException(oracleId, ex);
        } catch (RestClientException ex) {
            throw new CardManagerUnavailableException("Card Manager is unavailable", ex);
        }
    }
}
