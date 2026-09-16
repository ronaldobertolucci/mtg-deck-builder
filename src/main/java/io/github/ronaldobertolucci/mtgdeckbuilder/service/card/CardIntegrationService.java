package io.github.ronaldobertolucci.mtgdeckbuilder.service.card;

import io.github.ronaldobertolucci.mtgdeckbuilder.config.CardManagerProperties;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
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

import java.util.List;
import java.util.UUID;

@Service
public class CardIntegrationService {

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
        if (!StringUtils.hasText(properties.url())) {
            throw new CardManagerUnavailableException("Card Manager URL is not configured", null);
        }
        try {
            List<CardDetailsResponse> cards = restClient.get()
                    .uri("/cards/search?lang=en&name_exact={name}&limit=1", name)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<CardDetailsResponse>>() {});
            if (cards == null) {
                throw new CardManagerUnavailableException("Card Manager returned an empty response", null);
            }
            if (cards.isEmpty()) {
                throw new RuleViolationException("Card not found by name: " + name);
            }
            return cards.getFirst();
        } catch (HttpClientErrorException.NotFound ex) {
            throw new RuleViolationException("Card not found by name: " + name);
        } catch (RestClientException ex) {
            throw new CardManagerUnavailableException("Card Manager is unavailable", ex);
        }
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
