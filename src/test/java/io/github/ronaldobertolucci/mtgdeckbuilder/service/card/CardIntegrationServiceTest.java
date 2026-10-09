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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", ",\"color_identity\":null", ",\"color_identity\":[]"})
    void preservesUnknownAndColorlessIdentityThroughHttpAndCache(String field) {
        server.expect(once(), requestTo(CARD_URL + ORACLE_ID + "?lang=en"))
                .andRespond(withSuccess("{\"oracle_id\":\"" + ORACLE_ID + "\"" + field + "}", MediaType.APPLICATION_JSON));
        var card = service.fetchCardDetails(ORACLE_ID);
        if (field.endsWith("[]")) assertThat(card.colorIdentity()).isEmpty();
        else assertThat(card.colorIdentity()).isNull();
        assertThat(service.fetchCardDetails(ORACLE_ID)).isSameAs(card);
        server.verify();
    }

    @Test
    void deserializesProducedManaAndKeepsItInCache() {
        server.expect(once(), requestTo(CARD_URL + ORACLE_ID + "?lang=en"))
                .andRespond(withSuccess("""
                        {"oracle_id":"%s","cmc":2.0,"produced_mana":["W","U","B","R","G"]}
                        """.formatted(ORACLE_ID), MediaType.APPLICATION_JSON));
        var card = service.fetchCardDetails(ORACLE_ID);
        assertThat(card.cmc()).isEqualTo(2.0);
        assertThat(card.producedMana()).containsExactly("W", "U", "B", "R", "G");
        assertThat(service.fetchCardDetails(ORACLE_ID)).isSameAs(card);
        server.verify();
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
        assertThat(card.producedMana()).isEmpty();
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
                        {"items":[{"oracle_id":"%s","name":"Lightning Bolt","type_line":"Instant"}],
                         "limit":1,"offset":0,"hasNext":false}
                        """.formatted(ORACLE_ID), MediaType.APPLICATION_JSON));
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
                        {"items":[{"oracle_id":"%s","name":"Lightning Bolt","type_line":"Instant"}],
                         "limit":1,"offset":0,"hasNext":false}
                        """.formatted(ORACLE_ID), MediaType.APPLICATION_JSON));
        server.expect(requestTo(CARD_URL + "search?lang=en&name_exact=lightning%20bolt&limit=1"))
                .andRespond(withSuccess("{\"items\":[],\"limit\":1,\"offset\":0,\"hasNext\":false}", MediaType.APPLICATION_JSON));
        expectFrontFaceSearch("lightning bolt", 0, """
                {"items":[{"oracle_id":"%s","name":"Lightning Bolt"}],"limit":100,"offset":0,"hasNext":false}
                """.formatted(ORACLE_ID), false);

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
                .andRespond(withSuccess("{\"items\":[],\"limit\":1,\"offset\":0,\"hasNext\":false}", MediaType.APPLICATION_JSON));
        expectFrontFaceSearch("Missing", 0, "{\"items\":[],\"limit\":100,\"offset\":0,\"hasNext\":false}", false);
        assertThatThrownBy(() -> service.fetchCardDetailsByName("Missing"))
                .isInstanceOf(io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException.class)
                .hasMessageContaining("Missing");
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"jace", "esika", "invasion"})
    void resolvesAndCachesFrontFaceFromCapturedCatalogPayload(String fixture) throws IOException {
        String payload;
        try (var stream = getClass().getResourceAsStream("/cards/" + fixture + ".json")) {
            payload = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var expected = tools.jackson.databind.json.JsonMapper.builder().build().readValue(payload,
                io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse.class);
        String name = expected.cardFaces().getFirst().name();
        expectExactNameMissing(name, false);
        expectFrontFaceSearch(name, 0, "{\"items\":[" + payload + "],\"limit\":100,\"offset\":0,\"hasNext\":false}", false);

        var card = service.fetchCardDetailsByName(name);
        assertThat(card).isEqualTo(expected);
        assertThat(card.name()).isNotEqualTo(name);
        assertThat(service.fetchCardDetailsByName(name)).isSameAs(card);
        assertThat(cacheManager.getCache("cards_by_name").get(name).get()).isSameAs(card);
        server.verify();
    }

    @Test
    void combinedNameUsesExactSearchWithoutFallback() {
        server.expect(requestTo(CARD_URL + "search?lang=en&name_exact=Front%20%2F%2F%20Back&limit=1"))
                .andRespond(withSuccess("{\"items\":[" + doubleFaceJson(ORACLE_ID, "Front", "Back")
                        + "],\"limit\":1,\"offset\":0,\"hasNext\":false}", MediaType.APPLICATION_JSON));
        assertThat(service.fetchCardDetailsByName("Front // Back").oracleId()).isEqualTo(ORACLE_ID);
        server.verify();
    }

    @Test
    void skipsPartialAndBackFaceMatchesBeforeExactFrontFace() {
        expectExactNameMissing("Front", false);
        expectFrontFaceSearch("Front", 0, """
                {"items":[%s,%s,{"name":"Frontier"},%s],"limit":100,"offset":0,"hasNext":false}
                """.formatted(doubleFaceJson(UUID.randomUUID(), "Frontier", "Other"),
                doubleFaceJson(UUID.randomUUID(), "Other", "Front"),
                doubleFaceJson(ORACLE_ID, "Front", "Back")), false);
        assertThat(service.fetchCardDetailsByName("Front").oracleId()).isEqualTo(ORACLE_ID);
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"front", "Fron", "Back"})
    void rejectsWrongCasePartialAndBackFaceNames(String name) {
        expectExactNameMissing(name, false);
        expectFrontFaceSearch(name, 0, "{\"items\":[" + doubleFaceJson(ORACLE_ID, "Front", "Back")
                + "],\"limit\":100,\"offset\":0,\"hasNext\":false}", false);
        assertThatThrownBy(() -> service.fetchCardDetailsByName(name))
                .isInstanceOf(io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException.class)
                .hasMessage("Card not found by name: " + name);
        assertThat(cacheManager.getCache("cards_by_name").get(name)).isNull();
        server.verify();
    }

    @Test
    void findsFrontFaceOnLaterSearchPage() {
        expectExactNameMissing("Front", false);
        expectFrontFaceSearch("Front", 0, "{\"items\":[{\"name\":\"Frontier\"}],\"limit\":100,\"offset\":0,\"hasNext\":true}", false);
        expectFrontFaceSearch("Front", 1, "{\"items\":[" + doubleFaceJson(ORACLE_ID, "Front", "Back")
                + "],\"limit\":100,\"offset\":1,\"hasNext\":false}", false);
        assertThat(service.fetchCardDetailsByName("Front").oracleId()).isEqualTo(ORACLE_ID);
        server.verify();
    }

    @Test
    void rejectsAmbiguousFrontFacesAcrossPagesWithoutCaching() {
        expectExactNameMissing("Front", false);
        expectFrontFaceSearch("Front", 0, "{\"items\":[" + doubleFaceJson(ORACLE_ID, "Front", "Back")
                + "],\"limit\":100,\"offset\":0,\"hasNext\":true}", false);
        expectFrontFaceSearch("Front", 1, "{\"items\":[" + doubleFaceJson(UUID.randomUUID(), "Front", "Other")
                + "],\"limit\":100,\"offset\":1,\"hasNext\":false}", false);
        assertThatThrownBy(() -> service.fetchCardDetailsByName("Front"))
                .isInstanceOf(io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException.class)
                .hasMessage("Ambiguous front face name: Front");
        assertThat(cacheManager.getCache("cards_by_name").get("Front")).isNull();
        server.verify();
    }

    @Test
    void repeatedOracleIdAcrossPagesIsNotAmbiguous() {
        expectExactNameMissing("Front", false);
        String item = doubleFaceJson(ORACLE_ID, "Front", "Back");
        expectFrontFaceSearch("Front", 0, "{\"items\":[" + item + "],\"limit\":100,\"offset\":0,\"hasNext\":true}", false);
        expectFrontFaceSearch("Front", 1, "{\"items\":[" + item + "],\"limit\":100,\"offset\":1,\"hasNext\":false}", false);
        assertThat(service.fetchCardDetailsByName("Front").oracleId()).isEqualTo(ORACLE_ID);
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"null", "{}", "{\"items\":[null],\"limit\":100,\"offset\":0,\"hasNext\":false}",
            "{\"items\":[],\"limit\":100,\"offset\":0,\"hasNext\":true}",
            "{\"items\":[{\"card_faces\":[{\"name\":\"Front\"},{\"name\":\"Back\"}]}],\"limit\":100,\"offset\":0,\"hasNext\":false}"})
    void invalidFrontFaceSearchIsUnavailableAndNotCached(String response) {
        expectExactNameMissing("Front", false);
        expectFrontFaceSearch("Front", 0, response, false);
        assertThatThrownBy(() -> service.fetchCardDetailsByName("Front"))
                .isInstanceOf(CardManagerUnavailableException.class);
        assertThat(cacheManager.getCache("cards_by_name").get("Front")).isNull();
        server.verify();
    }

    @Test
    void frontFaceSearchHttpFailureIsUnavailableAndNotCached() {
        expectExactNameMissing("Front", false);
        server.expect(requestTo(CARD_URL + "search?lang=en&name=Front&limit=100&offset=0"))
                .andRespond(withServerError());
        assertThatThrownBy(() -> service.fetchCardDetailsByName("Front"))
                .isInstanceOf(CardManagerUnavailableException.class);
        assertThat(cacheManager.getCache("cards_by_name").get("Front")).isNull();
        server.verify();
    }

    @Test
    void frontFaceAccessorySearchPreservesIncludeTokens() {
        expectExactNameMissing("Front", true);
        expectFrontFaceSearch("Front", 0, "{\"items\":[" + doubleFaceJson(ORACLE_ID, "Front", "Back").replace("transform", "double_faced_token")
                + "],\"limit\":100,\"offset\":0,\"hasNext\":false}", true);
        var card = service.fetchAccessoryByName("Front");
        assertThat(card.oracleId()).isEqualTo(ORACLE_ID);
        assertThat(card.layout()).isEqualTo("double_faced_token");
        server.verify();
    }

    @Test
    void resolvesBatchesWithCamelCaseContractAndCachesIndividualPrintings() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        server.expect(requestTo(CARD_URL + "resolve")).andExpect(method(HttpMethod.POST))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().json(
                        "{\"ids\":[\"" + first + "\"]}"))
                .andRespond(withSuccess(resolvedJson(first), MediaType.APPLICATION_JSON));
        server.expect(requestTo(CARD_URL + "resolve")).andExpect(method(HttpMethod.POST))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().json(
                        "{\"ids\":[\"" + second + "\"]}"))
                .andRespond(withSuccess(resolvedJson(second), MediaType.APPLICATION_JSON));
        assertThat(service.resolveCards(java.util.List.of(first, first))).singleElement()
                .satisfies(c -> { assertThat(c.oracleId()).isEqualTo(ORACLE_ID); assertThat(c.isAccessory()).isTrue(); });
        assertThat(service.resolveCards(java.util.List.of(first, second))).hasSize(2);
        assertThat(service.resolveCards(java.util.List.of(second, first))).hasSize(2);
        server.verify();
    }

    @Test
    void incompleteResolutionFailsAndDoesNotCachePartialResults() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        server.expect(requestTo(CARD_URL + "resolve")).andRespond(withSuccess(resolvedJson(first), MediaType.APPLICATION_JSON));
        server.expect(requestTo(CARD_URL + "resolve")).andRespond(withSuccess(resolvedJson(first), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> service.resolveCards(java.util.List.of(first, second)))
                .isInstanceOf(CardManagerUnavailableException.class).hasMessageContaining("Incomplete");
        assertThat(service.resolveCards(java.util.List.of(first))).hasSize(1);
        server.verify();
    }

    @Test
    void emptyResolutionMakesNoRequest() {
        assertThat(service.resolveCards(java.util.List.of())).isEmpty();
        server.verify();
    }

    @Test
    void deserializesRelatedPrintingIdsAndLayout() {
        UUID printing = UUID.randomUUID();
        server.expect(requestTo(CARD_URL + ORACLE_ID + "?lang=en"))
                .andRespond(withSuccess("""
                    {"oracle_id":"%s","layout":"normal","all_parts":[{"id":"%s","component":"token","name":"Token"}]}
                    """.formatted(ORACLE_ID, printing), MediaType.APPLICATION_JSON));
        var card = service.fetchCardDetails(ORACLE_ID);
        assertThat(card.layout()).isEqualTo("normal");
        assertThat(card.allParts()).extracting(io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.RelatedCard::id).containsExactly(printing);
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"[]", "[null]", "[{}]", "null"})
    void rejectsIncompleteOrMalformedResolution(String response) {
        server.expect(requestTo(CARD_URL + "resolve")).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> service.resolveCards(java.util.List.of(UUID.randomUUID())))
                .isInstanceOf(CardManagerUnavailableException.class);
        server.verify();
    }

    @Test
    void resolutionHttpFailureCanBeRetried() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo(CARD_URL + "resolve")).andRespond(withServerError());
        server.expect(requestTo(CARD_URL + "resolve")).andRespond(withSuccess(resolvedJson(id), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> service.resolveCards(java.util.List.of(id))).isInstanceOf(CardManagerUnavailableException.class);
        assertThat(service.resolveCards(java.util.List.of(id))).hasSize(1);
        server.verify();
    }

    @Test
    void boundsResolutionPayloadToOneHundredIds() {
        var ids = java.util.stream.IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID()).toList();
        for (var batch : java.util.List.of(ids.subList(0, 100), ids.subList(100, 101))) {
            String request = "{\"ids\":[" + batch.stream().map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(",")) + "]}";
            String response = "[" + batch.stream().map(id -> resolvedJson(id).strip().substring(1, resolvedJson(id).strip().length() - 1))
                    .collect(java.util.stream.Collectors.joining(",")) + "]";
            server.expect(requestTo(CARD_URL + "resolve"))
                    .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().json(request))
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        }
        assertThat(service.resolveCards(ids)).hasSize(101);
        server.verify();
    }

    @Test
    void manualTokenNameSearchIncludesTokens() {
        server.expect(requestTo(CARD_URL + "search?lang=en&name_exact=Token&limit=1&include_tokens=true"))
                .andRespond(withSuccess("{\"items\":[{\"oracle_id\":\"" + ORACLE_ID + "\",\"layout\":\"token\"}],\"limit\":1,\"offset\":0,\"hasNext\":false}", MediaType.APPLICATION_JSON));
        assertThat(service.fetchAccessoryByName("Token").layout()).isEqualTo("token");
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "null", "{}", "{\"items\":null}",
            "{\"items\":[null]}", "[]", "{", "{\"items\":{}}"})
    void invalidNameSearchIsUnavailableForCardsAndAccessories(String response) {
        for (boolean accessory : new boolean[]{false, true}) {
            server.expect(requestTo(CARD_URL + "search?lang=en&name_exact=Invalid&limit=1"
                            + (accessory ? "&include_tokens=true" : "")))
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        }
        assertThatThrownBy(() -> service.fetchCardDetailsByName("Invalid"))
                .isInstanceOf(CardManagerUnavailableException.class);
        assertThatThrownBy(() -> service.fetchAccessoryByName("Invalid"))
                .isInstanceOf(CardManagerUnavailableException.class);
        assertThat(cacheManager.getCache("cards_by_name").get("Invalid")).isNull();
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {400, 404, 500, 503})
    void nameSearchHttpFailuresAreUnavailable(int status) {
        server.expect(requestTo(CARD_URL + "search?lang=en&name_exact=Missing&limit=1"))
                .andRespond(withStatus(HttpStatus.valueOf(status)));
        assertThatThrownBy(() -> service.fetchCardDetailsByName("Missing"))
                .isInstanceOf(CardManagerUnavailableException.class)
                .hasCauseInstanceOf(org.springframework.web.client.RestClientException.class);
        server.verify();
    }

    @Test
    void nameSearchConnectionFailureIsUnavailable() {
        server.expect(requestTo(CARD_URL + "search?lang=en&name_exact=Missing&limit=1"))
                .andRespond(withException(new IOException("Connection refused")));
        assertThatThrownBy(() -> service.fetchCardDetailsByName("Missing"))
                .isInstanceOf(CardManagerUnavailableException.class);
        server.verify();
    }

    @Test
    void accessorySearchWithNoItemsIsNotFound() {
        server.expect(requestTo(CARD_URL + "search?lang=en&name_exact=Missing&limit=1&include_tokens=true"))
                .andRespond(withSuccess("{\"items\":[],\"limit\":1,\"offset\":0,\"hasNext\":false}", MediaType.APPLICATION_JSON));
        expectFrontFaceSearch("Missing", 0, "{\"items\":[],\"limit\":100,\"offset\":0,\"hasNext\":false}", true);
        assertThatThrownBy(() -> service.fetchAccessoryByName("Missing"))
                .isInstanceOf(io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException.class);
        server.verify();
    }

    @Test
    void nameSearchPreservesRestoredCardFields() {
        UUID printing = UUID.randomUUID();
        server.expect(requestTo(CARD_URL + "search?lang=en&name_exact=Example&limit=1"))
                .andRespond(withSuccess("""
                        {"items":[{"oracle_id":"%s","name":"Example","keywords":["Companion"],
                         "all_parts":[{"id":"%s","component":"token","name":"Token"}],
                         "produced_mana":["G"],"rarity":"rare"}],"limit":1,"offset":0,"hasNext":false}
                        """.formatted(ORACLE_ID, printing), MediaType.APPLICATION_JSON));
        var card = service.fetchCardDetailsByName("Example");
        assertThat(card.keywords()).containsExactly("Companion");
        assertThat(card.allParts()).extracting(io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.RelatedCard::id)
                .containsExactly(printing);
        assertThat(card.producedMana()).containsExactly("G");
        assertThat(card.rarity()).isEqualTo("rare");
        assertThat(service.fetchCardDetailsByName("Example")).isSameAs(card);
        server.verify();
    }

    private void expectExactNameMissing(String name, boolean includeTokens) {
        String encoded = org.springframework.web.util.UriUtils.encode(name, java.nio.charset.StandardCharsets.UTF_8);
        server.expect(requestTo(CARD_URL + "search?lang=en&name_exact=" + encoded + "&limit=1"
                        + (includeTokens ? "&include_tokens=true" : "")))
                .andRespond(withSuccess("{\"items\":[],\"limit\":1,\"offset\":0,\"hasNext\":false}", MediaType.APPLICATION_JSON));
    }

    private void expectFrontFaceSearch(String name, int offset, String response, boolean includeTokens) {
        String encoded = org.springframework.web.util.UriUtils.encode(name, java.nio.charset.StandardCharsets.UTF_8);
        server.expect(requestTo(CARD_URL + "search?lang=en&name=" + encoded + "&limit=100&offset=" + offset
                        + (includeTokens ? "&include_tokens=true" : "")))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private String doubleFaceJson(UUID oracleId, String front, String back) {
        return """
                {"oracle_id":"%s","name":"%s // %s","layout":"transform",
                 "card_faces":[{"name":"%s"},{"name":"%s"}]}
                """.formatted(oracleId, front, back, front, back);
    }

    private String resolvedJson(UUID id) {
        return """
                [{"id":"%s","oracleId":"%s","name":"Token","layout":"token","typeLine":"Token Creature"}]
                """.formatted(id, ORACLE_ID);
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
