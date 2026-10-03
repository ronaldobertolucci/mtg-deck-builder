package io.github.ronaldobertolucci.mtgdeckbuilder.controller;

import io.github.ronaldobertolucci.mtgdeckbuilder.config.CardManagerConfiguration;
import io.github.ronaldobertolucci.mtgdeckbuilder.config.security.SecurityConfigurations;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.DeckService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation.CommanderValidator;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.security.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.test.autoconfigure.AutoConfigureMockRestServiceServer;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = DeckController.class, properties = "services.card-manager.url=http://card-manager.test")
@Import({SecurityConfigurations.class, DeckService.class, io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.DeckImportParserService.class, CommanderValidator.class, io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation.Constructed60Validator.class,
        CardRuleOverrideService.class, CardIntegrationService.class, CardManagerConfiguration.class})
@ImportAutoConfiguration({RestClientAutoConfiguration.class, CacheAutoConfiguration.class})
@AutoConfigureMockRestServiceServer
class CommanderCreationFlowTest {
    @MockitoBean io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.DeckExportService exportService;
    @MockitoBean io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.DeckStatsService statsService;
    @MockitoBean io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.ManaSuggestionService manaSuggestionService;
    @Autowired MockMvc mvc;
    @Autowired MockRestServiceServer server;
    @Autowired CacheManager cacheManager;
    @MockitoBean DeckRepository decks;
    @MockitoBean UserRepository users;
    @MockitoBean TokenService tokens;

    @BeforeEach void clearCache() { cacheManager.getCache("cards").clear(); cacheManager.getCache("cards_by_name").clear(); }

    @org.junit.jupiter.api.Test
    void importReturns422WhenCardManagerCannotFindExactCommanderName() throws Exception {
        when(decks.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        server.expect(requestTo("http://card-manager.test/cards/search?lang=en&name_exact=Missing&limit=1"))
                .andRespond(withSuccess("{\"items\":[],\"limit\":1,\"offset\":0,\"hasNext\":false}", MediaType.APPLICATION_JSON));
        User user = new User();
        user.setId(42L);
        mvc.perform(post("/decks/import")
                        .with(authentication(new UsernamePasswordAuthenticationToken(user, null, List.of())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Missing commander","format":"COMMANDER","rawText":"Commander\\n1 Missing"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("Card not found by name: Missing"));
        verify(decks, times(1)).saveAndFlush(any());
        server.verify();
    }

    @org.junit.jupiter.api.Test
    void importsCardsAndManualAndAutomaticAccessoriesUsingHttpContracts() throws Exception {
        UUID bolt = UUID.randomUUID(), manual = UUID.randomUUID(), automatic = UUID.randomUUID(), printing = UUID.randomUUID();
        when(decks.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        server.expect(requestTo("http://card-manager.test/cards/search?lang=en&name_exact=Lightning%20Bolt&limit=1"))
                .andRespond(withSuccess("""
                        {"items":[{"oracle_id":"%s","name":"Lightning Bolt","type_line":"Instant",
                         "legalities":{"modern":"legal"},"all_parts":[{"id":"%s"}]}],
                         "limit":1,"offset":0,"hasNext":false}
                        """.formatted(bolt, printing), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://card-manager.test/cards/search?lang=en&name_exact=Soldier&limit=1&include_tokens=true"))
                .andRespond(withSuccess("""
                        {"items":[{"oracle_id":"%s","name":"Soldier","type_line":"Token Creature","layout":"token"}],
                         "limit":1,"offset":0,"hasNext":false}
                        """.formatted(manual), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://card-manager.test/cards/resolve"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.POST))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().json(
                        "{\"ids\":[\"" + printing + "\"]}"))
                .andRespond(withSuccess("""
                        [{"id":"%s","oracleId":"%s","name":"Goblin","layout":"token","typeLine":"Token Creature"}]
                        """.formatted(printing, automatic), MediaType.APPLICATION_JSON));
        User user = new User(); user.setId(42L);
        mvc.perform(post("/decks/import")
                        .with(authentication(new UsernamePasswordAuthenticationToken(user, null, List.of())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Imported","format":"MODERN","rawText":"2 Lightning Bolt\\nTokens\\n1 Soldier"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cards.length()").value(3))
                .andExpect(jsonPath("$.cards[0].oracleId").value(bolt.toString()))
                .andExpect(jsonPath("$.cards[0].quantity").value(2))
                .andExpect(jsonPath("$.cards[1].oracleId").value(manual.toString()))
                .andExpect(jsonPath("$.cards[1].boardType").value("TOKENS"))
                .andExpect(jsonPath("$.cards[1].isAutoGenerated").value(false))
                .andExpect(jsonPath("$.cards[2].oracleId").value(automatic.toString()))
                .andExpect(jsonPath("$.cards[2].isAutoGenerated").value(true));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "{\"items\":null}", "{"})
    void importReturns503ForInvalidSearchResponse(String response) throws Exception {
        when(decks.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        server.expect(requestTo("http://card-manager.test/cards/search?lang=en&name_exact=Invalid&limit=1"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        User user = new User(); user.setId(42L);
        mvc.perform(post("/decks/import")
                        .with(authentication(new UsernamePasswordAuthenticationToken(user, null, List.of())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Invalid","format":"MODERN","rawText":"1 Invalid"}
                                """))
                .andExpect(status().isServiceUnavailable());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"banned", "BANNED", "not_legal"})
    void rejectsCommanderUsingRealHttpMappingServiceAndStrategy(String legality) throws Exception {
        UUID oracleId = UUID.randomUUID();
        server.expect(requestTo("http://card-manager.test/cards/" + oracleId + "?lang=en"))
                .andRespond(withSuccess("""
                        {"oracle_id":"%s","name":"Rejected Commander","type_line":"Legendary Creature",
                         "oracle_text":"","color_identity":["U"],
                         "legalities":{"modern":"legal","commander":"%s"}}
                        """.formatted(oracleId, legality), MediaType.APPLICATION_JSON));
        User user = new User();
        user.setId(42L);
        mvc.perform(post("/decks")
                        .with(authentication(new UsernamePasswordAuthenticationToken(user, null, List.of())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Commander","format":"COMMANDER","commanderOracleIds":["%s"]}
                                """.formatted(oracleId)))
                .andExpect(status().is(422))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(422));
        verifyNoInteractions(decks);
        server.verify();
    }
}
