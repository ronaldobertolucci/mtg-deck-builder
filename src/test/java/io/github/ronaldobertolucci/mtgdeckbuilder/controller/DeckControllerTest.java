package io.github.ronaldobertolucci.mtgdeckbuilder.controller;

import io.github.ronaldobertolucci.mtgdeckbuilder.config.security.SecurityConfigurations;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Format;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.UserRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.DeckService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.security.TokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.util.List;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DeckController.class)
@Import(SecurityConfigurations.class)
class DeckControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean DeckService service;
    @MockitoBean TokenService tokenService;
    @MockitoBean UserRepository users;
    private final UUID deckId = UUID.randomUUID();
    private final UUID oracleId = UUID.randomUUID();

    private RequestPostProcessor owner() {
        User user = new User();
        user.setId(42L);
        return authentication(new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("USER"))));
    }
    private DeckResponse response() {
        return new DeckResponse(deckId, "Modern", Format.MODERN, null, null, List.of());
    }

    @Test void createsDeckWithAuthenticatedUserId() throws Exception {
        when(service.create(eq(42L), any())).thenReturn(response());
        mvc.perform(post("/decks").with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Modern\",\"format\":\"MODERN\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "http://localhost/decks/" + deckId))
                .andExpect(jsonPath("$.id").value(deckId.toString()));
        verify(service).create(42L, new CreateDeckRequest("Modern", Format.MODERN, null));
    }

    @ParameterizedTest @ValueSource(ints = {4, 2, 0})
    void upsertsOrRemovesWith200(int quantity) throws Exception {
        when(service.upsertCard(eq(42L), eq(deckId), any())).thenReturn(response());
        mvc.perform(put("/decks/{id}/cards", deckId).with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"oracleId\":\"" + oracleId + "\",\"boardType\":\"MAINBOARD\",\"quantity\":" + quantity + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(deckId.toString()));
        verify(service).upsertCard(eq(42L), eq(deckId), argThat(request -> request.quantity() == quantity));
    }

    @Test void ruleViolationIsRfc7807() throws Exception {
        when(service.upsertCard(eq(42L), eq(deckId), any())).thenThrow(new RuleViolationException("Copy limit exceeded"));
        mvc.perform(put("/decks/{id}/cards", deckId).with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"oracleId\":\"" + oracleId + "\",\"boardType\":\"MAINBOARD\",\"quantity\":5}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("Copy limit exceeded"))
                .andExpect(jsonPath("$.instance").value("/decks/" + deckId + "/cards"))
                .andExpect(jsonPath("$.message").doesNotExist()).andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test void creationRuleViolationIsAlsoProblemDetail() throws Exception {
        when(service.create(eq(42L), any())).thenThrow(new RuleViolationException("Invalid commander"));
        mvc.perform(post("/decks").with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Deck\",\"format\":\"COMMANDER\",\"commanderOracleIds\":[\"" + oracleId + "\"]}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("Invalid commander"));
    }

    @Test void commanderIdIsConditionallyRequired() throws Exception {
        mvc.perform(post("/decks").with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Deck\",\"format\":\"COMMANDER\"}"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("commanderOracleIds"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"name\":\"\",\"format\":\"MODERN\"}",
            "{\"name\":\"Deck\",\"format\":\"INVALID\"}"})
    void invalidCreationReturns400(String json) throws Exception {
        mvc.perform(post("/decks").with(owner()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"oracleId\":\"invalid\",\"quantity\":1}",
            "{\"oracleId\":\"12345678-1234-1234-1234-123456789abc\",\"boardType\":\"MAINBOARD\",\"quantity\":-1}"})
    void invalidUpsertReturns400(String json) throws Exception {
        mvc.perform(put("/decks/{id}/cards", deckId).with(owner()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(service);
    }

    @Test void acceptsTwoCommanderIds() throws Exception {
        UUID second = UUID.randomUUID();
        when(service.create(eq(42L), any())).thenReturn(response());
        mvc.perform(post("/decks").with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Pair","format":"COMMANDER","commanderOracleIds":["%s","%s"]}
                        """.formatted(oracleId, second)))
                .andExpect(status().isCreated());
        verify(service).create(eq(42L), argThat(request -> request.commanderOracleIds().equals(List.of(oracleId, second))));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"name\":\"Deck\",\"format\":\"COMMANDER\",\"commanderOracleIds\":[]}",
        "{\"name\":\"Deck\",\"format\":\"COMMANDER\",\"commanderOracleIds\":[null]}",
        "{\"name\":\"Deck\",\"format\":\"COMMANDER\",\"commanderOracleIds\":[\"00000000-0000-0000-0000-000000000001\",\"00000000-0000-0000-0000-000000000001\"]}",
        "{\"name\":\"Deck\",\"format\":\"MODERN\",\"commanderOracleIds\":[\"00000000-0000-0000-0000-000000000001\"]}",
        "{\"name\":\"Deck\",\"format\":\"COMMANDER\",\"commanderOracleIds\":[\"00000000-0000-0000-0000-000000000001\",\"00000000-0000-0000-0000-000000000002\",\"00000000-0000-0000-0000-000000000003\"]}"
    })
    void invalidCommanderSelectionsReturn400(String json) throws Exception {
        mvc.perform(post("/decks").with(owner()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").isArray());
        verifyNoInteractions(service);
    }

    @Test void unauthenticatedCannotCreateDeck() throws Exception {
        mvc.perform(post("/decks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Deck\",\"format\":\"MODERN\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
