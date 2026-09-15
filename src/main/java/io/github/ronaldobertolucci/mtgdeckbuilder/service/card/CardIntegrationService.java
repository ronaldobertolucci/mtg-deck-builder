package io.github.ronaldobertolucci.mtgdeckbuilder.service.card;

import io.github.ronaldobertolucci.mtgdeckbuilder.config.CardManagerProperties;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.CardManagerUnavailableException;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.CardNotFoundException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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
