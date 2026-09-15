package io.github.ronaldobertolucci.mtgdeckbuilder.service.card;

import io.github.ronaldobertolucci.mtgdeckbuilder.config.CardManagerConfiguration;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.CardManagerUnavailableException;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.CardNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@RestClientTest(value = CardIntegrationService.class,
        properties = "services.card-manager.url=http://card-manager.test")
@Import(CardManagerConfiguration.class)
@ImportAutoConfiguration(CacheAutoConfiguration.class)
class CardIntegrationServiceTest {

    private static final UUID ORACLE_ID = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    // Mock host with the configured Card Manager route and required English response.
    private static final String CARD_URL = "http://card-manager.test/cards/";

    @Autowired
    private CardIntegrationService service;

    @Autowired
    private MockRestServiceServer server;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        cacheManager.getCache("cards").clear();
    }

    @Test
    void fetchCardDetailsDeserializesSuccessfulResponse() {
        expectCard(ORACLE_ID);

        var card = service.fetchCardDetails(ORACLE_ID);

        assertThat(card.oracleId()).isEqualTo(ORACLE_ID);
        assertThat(card.name()).isEqualTo("Lightning Bolt");
        assertThat(card.typeLine()).isEqualTo("Instant");
        assertThat(card.oracleText()).isEqualTo("Lightning Bolt deals 3 damage to any target.");
        assertThat(card.colorIdentity()).containsExactly("R");
        server.verify();
    }

    @Test
    void notFoundThrowsDomainException() {
        server.expect(requestTo(CARD_URL + ORACLE_ID + "?lang=en")).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> service.fetchCardDetails(ORACLE_ID))
                .isInstanceOf(CardNotFoundException.class)
                .hasMessageContaining(ORACLE_ID.toString())
                .hasCauseInstanceOf(HttpClientErrorException.class);
        server.verify();
    }

    @Test
    void serverErrorThrowsServiceUnavailableException() {
        server.expect(requestTo(CARD_URL + ORACLE_ID + "?lang=en")).andRespond(withServerError());

        assertThatThrownBy(() -> service.fetchCardDetails(ORACLE_ID))
                .isInstanceOf(CardManagerUnavailableException.class)
                .hasCauseInstanceOf(HttpServerErrorException.class);
        server.verify();
    }

    @Test
    void secondCallForSameOracleIdUsesCacheWithoutAnotherHttpRequest() {
        expectCard(ORACLE_ID);

        var first = service.fetchCardDetails(ORACLE_ID);
        var second = service.fetchCardDetails(ORACLE_ID);

        assertThat(second).isEqualTo(first);
        assertThat(cacheManager.getCache("cards").get(ORACLE_ID).get()).isEqualTo(first);
        server.verify();
    }

    @Test
    void differentOracleIdsMakeSeparateHttpRequests() {
        UUID otherId = UUID.randomUUID();
        expectCard(ORACLE_ID);
        expectCard(otherId);

        assertThat(service.fetchCardDetails(ORACLE_ID).oracleId()).isEqualTo(ORACLE_ID);
        assertThat(service.fetchCardDetails(otherId).oracleId()).isEqualTo(otherId);
        server.verify();
    }

    @Test
    void failedRequestIsNotCachedAndCanBeRetried() {
        server.expect(requestTo(CARD_URL + ORACLE_ID + "?lang=en")).andRespond(withServerError());
        expectCard(ORACLE_ID);

        assertThatThrownBy(() -> service.fetchCardDetails(ORACLE_ID))
                .isInstanceOf(CardManagerUnavailableException.class);
        assertThat(cacheManager.getCache("cards").get(ORACLE_ID)).isNull();
        assertThat(service.fetchCardDetails(ORACLE_ID).oracleId()).isEqualTo(ORACLE_ID);
        server.verify();
    }

    @Test
    void connectionFailureThrowsServiceUnavailableException() {
        server.expect(requestTo(CARD_URL + ORACLE_ID + "?lang=en"))
                .andRespond(withException(new IOException("Connection refused")));

        assertThatThrownBy(() -> service.fetchCardDetails(ORACLE_ID))
                .isInstanceOf(CardManagerUnavailableException.class);
        server.verify();
    }

    @Test
    void emptyResponseIsNotCached() {
        server.expect(requestTo(CARD_URL + ORACLE_ID + "?lang=en")).andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertThatThrownBy(() -> service.fetchCardDetails(ORACLE_ID))
                .isInstanceOf(CardManagerUnavailableException.class);
        assertThat(cacheManager.getCache("cards").get(ORACLE_ID)).isNull();
        server.verify();
    }

    @Test
    void caffeineUsesCapacityAndAccessExpirationFromApplicationProperties() {
        assertThat(cacheManager.getCache("cards")).isInstanceOf(CaffeineCache.class);
        var policy = ((CaffeineCache) cacheManager.getCache("cards")).getNativeCache().policy();

        assertThat(policy.eviction().orElseThrow().getMaximum()).isEqualTo(10_000);
        assertThat(policy.expireAfterAccess().orElseThrow().getExpiresAfter()).isEqualTo(Duration.ofHours(24));
    }

    private void expectCard(UUID oracleId) {
        server.expect(once(), requestTo(CARD_URL + oracleId + "?lang=en"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "oracleId": "%s",
                          "name": "Lightning Bolt",
                          "typeLine": "Instant",
                          "oracleText": "Lightning Bolt deals 3 damage to any target.",
                          "colorIdentity": ["R"]
                        }
                        """.formatted(oracleId), MediaType.APPLICATION_JSON));
    }
}
