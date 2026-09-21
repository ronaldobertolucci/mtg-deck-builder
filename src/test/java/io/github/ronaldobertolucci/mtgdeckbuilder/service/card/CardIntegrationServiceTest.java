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

    @Test
    void refreshBypassesAndUpdatesCache() {
        expectCard(ORACLE_ID);
        server.expect(requestTo(CARD_URL + ORACLE_ID + "?lang=en"))
                .andRespond(withSuccess("{\"oracle_id\":\"" + ORACLE_ID + "\",\"legalities\":{\"modern\":\"banned\"}}", MediaType.APPLICATION_JSON));
        service.fetchCardDetails(ORACLE_ID);
        var refreshed = service.refreshCardDetails(ORACLE_ID);
        assertThat(refreshed.legalities()).containsEntry("modern", io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardLegality.BANNED);
        assertThat(service.fetchCardDetails(ORACLE_ID)).isEqualTo(refreshed);
        server.verify();
    }

    @BeforeEach
    void clearCache() {
        cacheManager.getCache("cards").clear();
        cacheManager.getCache("cards_by_name").clear();
    }

    @Test
    void fetchCardDetailsDeserializesSuccessfulResponse() {
        expectCard(ORACLE_ID);

        var card = service.fetchCardDetails(ORACLE_ID);

        assertThat(card.oracleId()).isEqualTo(ORACLE_ID);
        assertThat(card.legalities()).containsEntry("modern", io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardLegality.LEGAL)
                .containsEntry("standard", io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardLegality.NOT_LEGAL)
                .containsEntry("legacy", io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardLegality.RESTRICTED)
                .containsEntry("commander", io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardLegality.BANNED);
        assertThat(card.cmc()).isEqualTo(1.0);
        assertThat(card.manaCost()).isEqualTo("{R}");
        assertThat(card.rarity()).isEqualTo("common");
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"omitted", "null", "present"})
    void readsCompanionKeywordsAndDefaultsMissingDataToEmpty(String scenario) {
        String keywords = switch (scenario) {
            case "present" -> ", \"keywords\": [\"Companion\", \"Vigilance\"]";
            case "null" -> ", \"keywords\": null";
            default -> "";
        };
        server.expect(requestTo(CARD_URL + ORACLE_ID + "?lang=en"))
                .andRespond(withSuccess("""
                        {"oracle_id":"%s","name":"Test companion","type_line":"Creature",
                         "oracle_text":"Companion — Test condition.","color_identity":[],"legalities":{"modern":"legal"}%s}
                        """.formatted(ORACLE_ID, keywords), MediaType.APPLICATION_JSON));
        var card = service.fetchCardDetails(ORACLE_ID);
        if (scenario.equals("present")) assertThat(card.keywords()).containsExactly("Companion", "Vigilance");
        else assertThat(card.keywords()).isEmpty();
        server.verify();
    }

    @Test
    void exactNameSearchExtractsFirstItemAndCachesExactCase() {
        server.expect(once(), requestTo(CARD_URL + "search?lang=en&name_exact=Lightning%20Bolt&limit=1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"oracle_id":"%s","name":"Lightning Bolt","type_line":"Instant"},
                         {"oracle_id":"%s","name":"Other","type_line":"Instant"}]
                        """.formatted(ORACLE_ID, UUID.randomUUID()), MediaType.APPLICATION_JSON));
        var first = service.fetchCardDetailsByName("Lightning Bolt");
        assertThat(first.oracleId()).isEqualTo(ORACLE_ID);
        assertThat(first.name()).isEqualTo("Lightning Bolt");
        assertThat(service.fetchCardDetailsByName("Lightning Bolt")).isEqualTo(first);
        assertThat(cacheManager.getCache("cards_by_name").get("Lightning Bolt").get()).isEqualTo(first);
        assertThat(cacheManager.getCache("cards").get(ORACLE_ID)).isNull();
        server.verify();
    }

    @Test
    void differentlyCasedNameDoesNotReuseCachedExactMatch() {
        server.expect(requestTo(CARD_URL + "search?lang=en&name_exact=Lightning%20Bolt&limit=1"))
                .andRespond(withSuccess("""
                        [{"oracle_id":"%s","name":"Lightning Bolt","type_line":"Instant"}]
                        """.formatted(ORACLE_ID), MediaType.APPLICATION_JSON));
        server.expect(requestTo(CARD_URL + "search?lang=en&name_exact=lightning%20bolt&limit=1"))
                .andRespond(withResourceNotFound());

        var card = service.fetchCardDetailsByName("Lightning Bolt");
        assertThatThrownBy(() -> service.fetchCardDetailsByName("lightning bolt"))
                .isInstanceOf(io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException.class);
        assertThat(cacheManager.getCache("cards_by_name").get("lightning bolt")).isNull();
        assertThat(service.fetchCardDetailsByName("Lightning Bolt")).isEqualTo(card);
        server.verify();
    }

    @Test
    void emptyNameSearchThrowsRuleViolationAndIsNotCached() {
        server.expect(requestTo(CARD_URL + "search?lang=en&name_exact=Missing&limit=1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> service.fetchCardDetailsByName("Missing"))
                .isInstanceOf(io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException.class)
                .hasMessageContaining("Missing");
        assertThat(cacheManager.getCache("cards_by_name").get("Missing")).isNull();
        server.verify();
    }

    @Test
    void exactNameNotFoundThrowsRuleViolationAndIsNotCached() {
        server.expect(requestTo(CARD_URL + "search?lang=en&name_exact=Missing&limit=1"))
                .andRespond(withResourceNotFound());
        assertThatThrownBy(() -> service.fetchCardDetailsByName("Missing"))
                .isInstanceOf(io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException.class)
                .hasMessage("Card not found by name: Missing");
        assertThat(cacheManager.getCache("cards_by_name").get("Missing")).isNull();
        server.verify();
    }

    @Test
    void nameSearchServerFailureIsNotCached() {
        server.expect(requestTo(CARD_URL + "search?lang=en&name_exact=Missing&limit=1"))
                .andRespond(withServerError());
        assertThatThrownBy(() -> service.fetchCardDetailsByName("Missing"))
                .isInstanceOf(CardManagerUnavailableException.class);
        assertThat(cacheManager.getCache("cards_by_name").get("Missing")).isNull();
        server.verify();
    }

    private void expectCard(UUID oracleId) {
        server.expect(once(), requestTo(CARD_URL + oracleId + "?lang=en"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "oracle_id": "%s",
                          "name": "Lightning Bolt",
                          "cmc": 1.0,
                          "mana_cost": "{R}",
                          "rarity": "common",
                          "type_line": "Instant",
                          "oracle_text": "Lightning Bolt deals 3 damage to any target.",
                          "color_identity": ["R"],
                          "legalities": {"standard": "not_legal", "modern": "legal", "legacy": "restricted", "commander": "banned"}
                        }
                        """.formatted(oracleId), MediaType.APPLICATION_JSON));
    }
}
