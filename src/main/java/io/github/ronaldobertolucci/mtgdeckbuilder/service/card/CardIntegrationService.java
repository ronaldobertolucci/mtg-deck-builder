package io.github.ronaldobertolucci.mtgdeckbuilder.service.card;

import io.github.ronaldobertolucci.mtgdeckbuilder.config.CardManagerProperties;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardSearchResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.ResolvedCardResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.CardManagerUnavailableException;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.CardNotFoundException;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
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

    private static final int NAME_SEARCH_PAGE_SIZE = 100;
    private static final Set<String> NON_DECK_LAYOUTS = Set.of(
            "token", "double_faced_token", "emblem", "art_series", "planar", "scheme", "vanguard");

    private final Cache<UUID, ResolvedCardResponse> resolvedCards =
            Caffeine.newBuilder().maximumSize(10000)
                    .expireAfterWrite(Duration.ofHours(24)).build();

    private final RestClient restClient;
    private final CardManagerProperties properties;
    private final org.springframework.cache.Cache cards;
    private final org.springframework.cache.Cache names;

    public CardIntegrationService(@Qualifier("cardManagerRestClient") RestClient restClient,
            CardManagerProperties properties, CacheManager cacheManager) {
        this.restClient = restClient;
        this.properties = properties;
        this.cards = Objects.requireNonNull(cacheManager.getCache("cards"));
        this.names = Objects.requireNonNull(cacheManager.getCache("cards_by_name"));
    }

    public CardDetailsResponse fetchCardDetails(UUID oracleId) {
        var cached = cards.get(oracleId, CardDetailsResponse.class);
        if (cached != null) return cached;
        var card = requestCardDetails(oracleId);
        // Do not overwrite a concurrent refresh or reset the age of an existing entry.
        var existing = cards.putIfAbsent(oracleId, card);
        return existing == null ? card : (CardDetailsResponse) existing.get();
    }

    public CardDetailsResponse refreshCardDetails(UUID oracleId) {
        var card = requestCardDetails(oracleId);
        cards.put(oracleId, card);
        return card;
    }

    public CardDetailsResponse fetchCardDetailsByName(String name) {
        UUID oracleId = names.get(name, UUID.class);
        if (oracleId != null) return fetchCardDetails(oracleId);
        var card = requestCardByName(name, false);
        // Names (including front-face aliases) share one canonical metadata entry.
        // A search must not overwrite an existing entry, possibly refreshed concurrently.
        var existing = cards.putIfAbsent(card.oracleId(), card);
        names.put(name, card.oracleId());
        return existing == null ? card : (CardDetailsResponse) existing.get();
    }

    public CardDetailsResponse fetchAccessoryByName(String name) {
        return requestCardByName(name, true);
    }

    private CardDetailsResponse requestCardByName(String name, boolean includeTokens) {
        if (!StringUtils.hasText(properties.url())) {
            throw new CardManagerUnavailableException("Card Manager URL is not configured", null);
        }
        try {
            CardDetailsResponse match = findCardByName(name, includeTokens, false);
            if (match == null) match = findCardByName(name, includeTokens, true);
            if (match == null) throw new RuleViolationException("Card not found by name: " + name);
            return match;
        } catch (RestClientException ex) {
            throw new CardManagerUnavailableException("Card Manager is unavailable", ex);
        }
    }

    private CardDetailsResponse findCardByName(String name, boolean includeTokens, boolean frontFace) {
        CardDetailsResponse match = null;
        int offset = 0;
        while (true) {
            CardSearchResponse response = searchCards(
                    "/cards/search?lang=en&" + (frontFace ? "name" : "name_exact")
                            + "={name}&limit=" + NAME_SEARCH_PAGE_SIZE + "&offset=" + offset,
                    name, includeTokens);
            for (CardDetailsResponse card : response.items()) {
                if (!isNameSearchCandidate(card, includeTokens)) continue;
                // Arena exports only the front name; a partial match or a back face is insufficient.
                if (frontFace && (card.cardFaces().size() < 2 || card.cardFaces().getFirst() == null
                        || !name.equals(card.cardFaces().getFirst().name()))) continue;
                if (card.oracleId() == null) {
                    throw new CardManagerUnavailableException("Card Manager returned a card without an oracle ID", null);
                }
                if (match != null && !match.oracleId().equals(card.oracleId())) {
                    throw new RuleViolationException((frontFace ? "Ambiguous front face name: " : "Ambiguous card name: ") + name);
                }
                match = card;
            }
            // Read every page to find later matches and detect ambiguous oracle IDs.
            if (!response.hasNext()) break;
            offset += response.items().size();
        }
        return match;
    }

    private boolean isNameSearchCandidate(CardDetailsResponse card, boolean accessory) {
        boolean isAccessory = ResolvedCardResponse.isAccessory(card.layout(), card.typeLine());
        if (accessory) return isAccessory;
        // Placeholder cards and non-deck components can share a playable card's exact name.
        return !isAccessory && !"Card".equals(card.typeLine())
                && (card.layout() == null || !NON_DECK_LAYOUTS.contains(card.layout()));
    }

    private CardSearchResponse searchCards(String path, String name, boolean includeTokens) {
        CardSearchResponse response = restClient.get()
                .uri(path + (includeTokens ? "&include_tokens=true" : ""), name)
                .retrieve()
                .body(CardSearchResponse.class);
        if (response == null || response.items() == null || response.items().contains(null)
                || (response.hasNext() && response.items().isEmpty())) {
            throw new CardManagerUnavailableException("Card Manager returned an invalid search response", null);
        }
        return response;
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
            if (card == null || !oracleId.equals(card.oracleId())) {
                throw new CardManagerUnavailableException("Card Manager returned empty or inconsistent card details", null);
            }
            return card;
        } catch (HttpClientErrorException ex) {
            throw new CardNotFoundException(oracleId, ex);
        } catch (RestClientException ex) {
            throw new CardManagerUnavailableException("Card Manager is unavailable", ex);
        }
    }
}
