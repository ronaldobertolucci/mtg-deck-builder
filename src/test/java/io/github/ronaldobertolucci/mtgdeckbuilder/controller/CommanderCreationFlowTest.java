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
@Import({SecurityConfigurations.class, DeckService.class, CommanderValidator.class,
        CardRuleOverrideService.class, CardIntegrationService.class, CardManagerConfiguration.class})
@ImportAutoConfiguration({RestClientAutoConfiguration.class, CacheAutoConfiguration.class})
@AutoConfigureMockRestServiceServer
class CommanderCreationFlowTest {
    @Autowired MockMvc mvc;
    @Autowired MockRestServiceServer server;
    @Autowired CacheManager cacheManager;
    @MockitoBean DeckRepository decks;
    @MockitoBean UserRepository users;
    @MockitoBean TokenService tokens;

    @BeforeEach void clearCache() { cacheManager.getCache("cards").clear(); }

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
                                {"name":"Commander","format":"COMMANDER","commanderOracleId":"%s"}
                                """.formatted(oracleId)))
                .andExpect(status().is(422))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(422));
        verifyNoInteractions(decks);
        server.verify();
    }
}
