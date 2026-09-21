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
    @MockitoBean io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.DeckExportService exportService;
    @MockitoBean io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.DeckStatsService statsService;
    @MockitoBean io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.ManaSuggestionService manaSuggestionService;
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
        return new DeckResponse(deckId, "Modern", Format.MODERN, null, null, List.of(), io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.DeckStatus.UNDEFINED, null, List.of());
    }

    @Test void suggests36LandsByDefaultForAuthenticatedOwner() throws Exception {
        when(manaSuggestionService.suggestManaBase(deckId, 42L, 36))
                .thenReturn(new ManaSuggestionResponse(java.util.Map.of("WHITE", 0, "BLUE", 18,
                        "BLACK", 0, "RED", 0, "GREEN", 18)));
        mvc.perform(get("/api/decks/{id}/mana-suggestion", deckId).contextPath("/api").with(owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedBasicLands.BLUE").value(18))
                .andExpect(jsonPath("$.suggestedBasicLands.GREEN").value(18))
                .andExpect(jsonPath("$.suggestedBasicLands.WHITE").value(0));
        verify(manaSuggestionService).suggestManaBase(deckId, 42L, 36);
    }

    @ParameterizedTest @ValueSource(ints = {0, 24, 40})
    void acceptsCustomManaTargetAndEmptySuggestion(int target) throws Exception {
        when(manaSuggestionService.suggestManaBase(deckId, 42L, target))
                .thenReturn(new ManaSuggestionResponse(java.util.Map.of()));
        mvc.perform(get("/api/decks/{id}/mana-suggestion", deckId).contextPath("/api")
                        .param("targetLands", Integer.toString(target)).with(owner()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.suggestedBasicLands").isEmpty());
        verify(manaSuggestionService).suggestManaBase(deckId, 42L, target);
    }

    @Test void manaSuggestionRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/decks/{id}/mana-suggestion", deckId).contextPath("/api"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(manaSuggestionService);
    }

    @Test void manaSuggestionOfMissingOrUnownedDeckReturns404() throws Exception {
        when(manaSuggestionService.suggestManaBase(deckId, 42L, 36))
                .thenThrow(new io.github.ronaldobertolucci.mtgdeckbuilder.exception.DeckNotFoundException());
        mvc.perform(get("/api/decks/{id}/mana-suggestion", deckId).contextPath("/api").with(owner()))
                .andExpect(status().isNotFound());
    }

    @Test void negativeManaTargetReturns400() throws Exception {
        when(manaSuggestionService.suggestManaBase(deckId, 42L, -1))
                .thenThrow(new IllegalArgumentException("targetLands must be non-negative"));
        mvc.perform(get("/decks/{id}/mana-suggestion", deckId).param("targetLands", "-1").with(owner()))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest @ValueSource(strings = {"abc", "1.5", "2147483648"})
    void rejectsMalformedManaTarget(String target) throws Exception {
        mvc.perform(get("/decks/{id}/mana-suggestion", deckId).param("targetLands", target).with(owner()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(manaSuggestionService);
    }

    @Test void returnsStatsForAuthenticatedOwner() throws Exception {
        when(statsService.getDeckStats(deckId, 42L)).thenReturn(new DeckStatsResponse(4, 2.67,
                java.util.Map.of("0", 0, "7+", 1), java.util.Map.of("Creature", 3, "Land", 1),
                java.util.Map.of("BLUE", 6), java.util.Map.of("RARE", 4)));
        mvc.perform(get("/api/decks/{id}/stats", deckId).contextPath("/api").with(owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCards").value(4))
                .andExpect(jsonPath("$.averageCmc").value(2.67))
                .andExpect(jsonPath("$.manaCurve['7+']").value(1))
                .andExpect(jsonPath("$.typeDistribution.Creature").value(3))
                .andExpect(jsonPath("$.colorPips.BLUE").value(6))
                .andExpect(jsonPath("$.rarityDistribution.RARE").value(4));
        verify(statsService).getDeckStats(deckId, 42L);
    }

    @Test void statsOfMissingOrUnownedDeckReturns404() throws Exception {
        when(statsService.getDeckStats(deckId, 42L))
                .thenThrow(new io.github.ronaldobertolucci.mtgdeckbuilder.exception.DeckNotFoundException());
        mvc.perform(get("/api/decks/{id}/stats", deckId).contextPath("/api").with(owner()))
                .andExpect(status().isNotFound());
    }

    @Test void statsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/decks/{id}/stats", deckId).contextPath("/api"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(statsService);
    }

    @Test void exportsArenaByDefaultWithAuthenticatedOwner() throws Exception {
        when(exportService.exportDeck(deckId, io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.ExportFormat.ARENA, 42L))
                .thenReturn(new ExportDeckResponse("Deck\n20 Island"));
        mvc.perform(get("/api/decks/{id}/export", deckId).contextPath("/api").with(owner()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("Deck\n20 Island"));
    }

    @Test void exportsPlainTextWhenRequested() throws Exception {
        when(exportService.exportDeck(deckId, io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.ExportFormat.PLAIN_TEXT, 42L))
                .thenReturn(new ExportDeckResponse("20 Island"));
        mvc.perform(get("/decks/{id}/export", deckId).param("format", "PLAIN_TEXT").with(owner()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("20 Island"));
    }

    @Test void exportOfMissingOrUnownedDeckReturns404() throws Exception {
        when(exportService.exportDeck(any(), any(), eq(42L)))
                .thenThrow(new io.github.ronaldobertolucci.mtgdeckbuilder.exception.DeckNotFoundException());
        mvc.perform(get("/decks/{id}/export", deckId).with(owner())).andExpect(status().isNotFound());
    }

    @Test void rejectsInvalidExportFormat() throws Exception {
        mvc.perform(get("/decks/{id}/export", deckId).param("format", "UNKNOWN").with(owner()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(exportService);
    }

    @Test void exportRequiresAuthentication() throws Exception {
        mvc.perform(get("/decks/{id}/export", deckId)).andExpect(status().isForbidden());
        verifyNoInteractions(exportService);
    }

    @Test void importsDeckWithAuthenticatedUserAndApiLocation() throws Exception {
        when(service.importDeck(eq(42L), any())).thenReturn(response());
        mvc.perform(post("/api/decks/import").contextPath("/api").with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Modern","format":"MODERN","rawText":"4 Lightning Bolt"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/decks/" + deckId));
        verify(service).importDeck(42L, new ImportDeckRequest("Modern", Format.MODERN, "4 Lightning Bolt"));
    }

    @Test void rejectsBlankImportText() throws Exception {
        mvc.perform(post("/decks/import").with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Modern","format":"MODERN","rawText":" "}
                        """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
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

    @ParameterizedTest @ValueSource(ints = {1, 0})
    void acceptsCompanionBoardForUpsertAndRemoval(int quantity) throws Exception {
        var result = new DeckResponse(deckId, "Test", Format.MODERN, null, null,
                quantity == 0 ? List.of() : List.of(new DeckCardResponse(UUID.randomUUID(), oracleId,
                        io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.BoardType.COMPANION, 1)), io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.DeckStatus.UNDEFINED, null, List.of());
        when(service.upsertCard(eq(42L), eq(deckId), any())).thenReturn(result);
        mvc.perform(put("/decks/{id}/cards", deckId).with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"oracleId":"%s","boardType":"COMPANION","quantity":%d}
                        """.formatted(oracleId, quantity)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cards.length()").value(quantity));
        verify(service).upsertCard(eq(42L), eq(deckId), argThat(request -> request.boardType()
                == io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.BoardType.COMPANION && request.quantity() == quantity));
    }

    @Test void unauthenticatedCannotCreateDeck() throws Exception {
        mvc.perform(post("/decks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Deck\",\"format\":\"MODERN\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
