package io.github.ronaldobertolucci.mtgdeckbuilder.controller;

import io.github.ronaldobertolucci.mtgdeckbuilder.config.security.SecurityConfigurations;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Format;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.UserRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.DeckService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.DeckImportParserService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DeckController.class)
@Import({io.github.ronaldobertolucci.mtgdeckbuilder.service.security.AuthenticationService.class, SecurityConfigurations.class})
class DeckControllerTest {
    @MockitoBean io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.DeckExportService exportService;
    @MockitoBean io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.DeckStatsService statsService;
    @MockitoBean io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.DeckCompositionService compositionService;
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

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "SIDEBOARD_SIZE_LIMIT_EXCEEDED,quantity", "INVALID_COMPANION_QUANTITY,quantity",
            "COMPANION_NOT_ELIGIBLE,oracleId", "COMPANION_LIMIT_EXCEEDED,boardType",
            "INVALID_COMMANDER_SELECTION,commanderOracleIds", "LAST_COMMANDER_REQUIRED,quantity",
            "BOARD_TYPE_NOT_SUPPORTED,boardType", "CARD_NOT_ACCESSORY,oracleId"
    })
    void cardRuleErrorsSerializeStructuredProblemDetails(
            io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleErrorCode code, String field) throws Exception {
        var otherId = UUID.randomUUID();
        when(service.upsertCard(eq(42L), eq(deckId), any())).thenThrow(new RuleViolationException(
                code, "Rule rejected", field, List.of(oracleId, otherId)));
        mvc.perform(put("/decks/{deckId}/cards", deckId).with(owner()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oracleId":"%s","boardType":"MAINBOARD","quantity":1}
                                """.formatted(oracleId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("Rule rejected"))
                .andExpect(jsonPath("$.code").value(code.name()))
                .andExpect(jsonPath("$.field").value(field))
                .andExpect(jsonPath("$.oracleIds[0]").value(oracleId.toString()))
                .andExpect(jsonPath("$.oracleIds[1]").value(otherId.toString()));
    }

    @Test void listsPaginatedSummariesForOwner() throws Exception {
        var summary = new DeckSummaryResponse(deckId, "Modern", Format.MODERN, null, null,
                io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.DeckStatus.UNDEFINED, null);
        when(service.list(42L, 1, 2)).thenReturn(new org.springframework.data.domain.PageImpl<>(
                List.of(summary), org.springframework.data.domain.PageRequest.of(1, 2), 3));
        mvc.perform(get("/decks").with(owner()).param("page", "1").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(deckId.toString()))
                .andExpect(jsonPath("$.content[0].cards").doesNotExist())
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.number").value(1));
    }

    @Test void listsEmptyPageWithDefaults() throws Exception {
        when(service.list(42L, 0, 20)).thenReturn(org.springframework.data.domain.Page.empty());
        mvc.perform(get("/decks").with(owner())).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
        verify(service).list(42L, 0, 20);
    }

    @ParameterizedTest @ValueSource(strings = {"-1", "abc", "2147483648"})
    void rejectsInvalidPage(String page) throws Exception {
        mvc.perform(get("/decks").with(owner()).param("page", page)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "101", "abc"})
    void rejectsInvalidPageSize(String size) throws Exception {
        mvc.perform(get("/decks").with(owner()).param("size", size)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void getsSavedStructuredAnalysis() throws Exception {
        var deck = new io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Deck(42L, "Modern", Format.MODERN);
        deck.recordStructuredAnalysis(io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.DeckStatus.UNDEFINED,
                java.time.Instant.parse("2026-09-15T12:00:00Z"), List.of(
                new io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.AnalysisReason("CARD_METADATA_UNAVAILABLE",
                        io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.AnalysisReason.Severity.UNCERTAINTY,
                        "Current card metadata unavailable: " + oracleId, java.util.Map.of("oracleId", oracleId.toString()))));
        when(service.get(42L, deckId)).thenReturn(DeckResponse.from(deck));
        mvc.perform(get("/decks/{id}", deckId).with(owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisReasons[0].code").value("CARD_METADATA_UNAVAILABLE"))
                .andExpect(jsonPath("$.analysisReasons[0].severity").value("UNCERTAINTY"))
                .andExpect(jsonPath("$.analysisReasons[0].parameters.oracleId").value(oracleId.toString()))
                .andExpect(jsonPath("$.analysisMessages[0]").value("Current card metadata unavailable: " + oracleId));
    }

    @Test void getsIndividualDeck() throws Exception {
        when(service.get(42L, deckId)).thenReturn(response());
        mvc.perform(get("/decks/{id}", deckId).with(owner())).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(deckId.toString())).andExpect(jsonPath("$.cards").isArray());
    }

    @Test void renamesDeck() throws Exception {
        when(service.rename(42L, deckId, new RenameDeckRequest("Modern"))).thenReturn(response());
        mvc.perform(patch("/decks/{id}", deckId).with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Modern\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Modern"));
        verify(service).rename(42L, deckId, new RenameDeckRequest("Modern"));
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\"   \"}"})
    void rejectsMissingOrBlankRename(String body) throws Exception {
        mvc.perform(patch("/decks/{id}", deckId).with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void rejectsLongName() throws Exception {
        mvc.perform(patch("/decks/{id}", deckId).with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + "a".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void deletesDeckWithoutResponseBody() throws Exception {
        mvc.perform(delete("/decks/{id}", deckId).with(owner())).andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(service).delete(42L, deckId);
    }

    @Test void crudRequiresAuthentication() throws Exception {
        mvc.perform(get("/decks")).andExpect(status().isUnauthorized());
        mvc.perform(get("/decks/{id}", deckId)).andExpect(status().isUnauthorized());
        mvc.perform(patch("/decks/{id}", deckId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"New\"}")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/decks/{id}", deckId)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void individualOperationsReturn404ForMissingOrUnownedDeck() throws Exception {
        when(service.get(42L, deckId)).thenThrow(new io.github.ronaldobertolucci.mtgdeckbuilder.exception.DeckNotFoundException());
        when(service.rename(eq(42L), eq(deckId), any())).thenThrow(new io.github.ronaldobertolucci.mtgdeckbuilder.exception.DeckNotFoundException());
        doThrow(new io.github.ronaldobertolucci.mtgdeckbuilder.exception.DeckNotFoundException()).when(service).delete(42L, deckId);
        mvc.perform(get("/decks/{id}", deckId).with(owner())).andExpect(status().isNotFound());
        mvc.perform(patch("/decks/{id}", deckId).with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"New\"}")).andExpect(status().isNotFound());
        mvc.perform(delete("/decks/{id}", deckId).with(owner())).andExpect(status().isNotFound());
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
                .andExpect(status().isUnauthorized());
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
        UUID oracleId = UUID.randomUUID();
        when(statsService.getDeckStats(deckId, 42L)).thenReturn(new DeckStatsResponse(4, 2.67,
                java.util.Map.of("0", 0, "7+", 1), java.util.Map.of("Creature", 3, "Land", 1),
                java.util.Map.of("BLUE", 6), java.util.Map.of("RARE", 4),
                java.util.Map.of("Creature", List.of(new DeckStatsCardResponse(oracleId,
                        io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.BoardType.MAINBOARD, 3)),
                        "Land", List.of(new DeckStatsCardResponse(UUID.randomUUID(),
                                io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.BoardType.COMMANDER, 1)),
                        "Other", List.of())));
        mvc.perform(get("/api/decks/{id}/stats", deckId).contextPath("/api").with(owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCards").value(4))
                .andExpect(jsonPath("$.averageCmc").value(2.67))
                .andExpect(jsonPath("$.manaCurve['7+']").value(1))
                .andExpect(jsonPath("$.typeDistribution.Creature").value(3))
                .andExpect(jsonPath("$.colorPips.BLUE").value(6))
                .andExpect(jsonPath("$.rarityDistribution.RARE").value(4))
                .andExpect(jsonPath("$.cardsByType.Creature[0].oracleId").value(oracleId.toString()))
                .andExpect(jsonPath("$.cardsByType.Creature[0].boardType").value("MAINBOARD"))
                .andExpect(jsonPath("$.cardsByType.Creature[0].quantity").value(3))
                .andExpect(jsonPath("$.cardsByType.Land[0].boardType").value("COMMANDER"))
                .andExpect(jsonPath("$.cardsByType.Other").isArray())
                .andExpect(jsonPath("$.cardsByType.Other").isEmpty());
        verify(statsService).getDeckStats(deckId, 42L);
    }

    @Test void returnsCompositionForAuthenticatedOwner() throws Exception {
        UUID oracleId = UUID.randomUUID();
        var board = io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.BoardType.TOKENS;
        var token = new DeckCardResponse(UUID.randomUUID(), oracleId, board, 3, true);
        when(compositionService.getDeckComposition(deckId, 42L)).thenReturn(new DeckCompositionResponse(
                java.util.Map.of(board, java.util.Map.of("Token", List.of(token), "Emblem", List.of()))));

        mvc.perform(get("/api/decks/{id}/composition", deckId).contextPath("/api").with(owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardsByBoardAndType.TOKENS.Token[0].id").value(token.id().toString()))
                .andExpect(jsonPath("$.cardsByBoardAndType.TOKENS.Token[0].oracleId").value(oracleId.toString()))
                .andExpect(jsonPath("$.cardsByBoardAndType.TOKENS.Token[0].boardType").value("TOKENS"))
                .andExpect(jsonPath("$.cardsByBoardAndType.TOKENS.Token[0].quantity").value(3))
                .andExpect(jsonPath("$.cardsByBoardAndType.TOKENS.Token[0].isAutoGenerated").value(true))
                .andExpect(jsonPath("$.cardsByBoardAndType.TOKENS.Emblem").isArray())
                .andExpect(jsonPath("$.cardsByBoardAndType.TOKENS.Emblem").isEmpty());
        verify(compositionService).getDeckComposition(deckId, 42L);
    }

    @Test void compositionOfMissingOrUnownedDeckReturns404() throws Exception {
        when(compositionService.getDeckComposition(deckId, 42L))
                .thenThrow(new io.github.ronaldobertolucci.mtgdeckbuilder.exception.DeckNotFoundException());
        mvc.perform(get("/api/decks/{id}/composition", deckId).contextPath("/api").with(owner()))
                .andExpect(status().isNotFound());
        verifyNoInteractions(statsService);
    }

    @Test void compositionRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/decks/{id}/composition", deckId).contextPath("/api"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(compositionService);
    }

    @Test void statsOfMissingOrUnownedDeckReturns404() throws Exception {
        when(statsService.getDeckStats(deckId, 42L))
                .thenThrow(new io.github.ronaldobertolucci.mtgdeckbuilder.exception.DeckNotFoundException());
        mvc.perform(get("/api/decks/{id}/stats", deckId).contextPath("/api").with(owner()))
                .andExpect(status().isNotFound());
    }

    @Test void statsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/decks/{id}/stats", deckId).contextPath("/api"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(statsService);
    }

    @Test void printCardsUsesBearerAuthenticatedUser() throws Exception {
        User user = new User();
        user.setId(42L);
        when(tokenService.getSubject("print-token")).thenReturn("owner@example.com");
        when(users.findByUsername("owner@example.com")).thenReturn(user);
        when(exportService.printCards(deckId, 42L))
                .thenReturn(new PrintDeckResponse("v1:opaque-revision", List.of(new PrintDeckCardResponse(oracleId, 5))));
        mvc.perform(get("/api/decks/{id}/print-cards", deckId).contextPath("/api")
                        .header("Authorization", "Bearer print-token"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"compositionRevision":"v1:opaque-revision","cards":[{"oracleId":"%s","quantity":5}]}
                        """.formatted(oracleId)));
        verify(exportService).printCards(deckId, 42L);
    }

    @Test void printCardsOfEmptyDeckReturnsEmptyArray() throws Exception {
        String revision = "v1:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        when(exportService.printCards(deckId, 42L)).thenReturn(new PrintDeckResponse(revision, List.of()));
        mvc.perform(get("/decks/{id}/print-cards", deckId).with(owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compositionRevision").value(revision))
                .andExpect(jsonPath("$.cards").isEmpty());
    }

    @Test void printCardsOfMissingOrUnownedDeckReturns404() throws Exception {
        when(exportService.printCards(deckId, 42L))
                .thenThrow(new io.github.ronaldobertolucci.mtgdeckbuilder.exception.DeckNotFoundException());
        mvc.perform(get("/decks/{id}/print-cards", deckId).with(owner()))
                .andExpect(status().isNotFound());
    }

    @Test void printCardsRequiresAuthentication() throws Exception {
        mvc.perform(get("/decks/{id}/print-cards", deckId)).andExpect(status().isUnauthorized());
        verifyNoInteractions(exportService);
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
        mvc.perform(get("/decks/{id}/export", deckId)).andExpect(status().isUnauthorized());
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

    @Test void importParserErrorExposesStructuredLine() throws Exception {
        var error = assertThrows(RuleViolationException.class,
                () -> new DeckImportParserService().parse("Deck\n\nbad line"));
        when(service.importDeck(eq(42L), any())).thenThrow(error);
        mvc.perform(post("/api/decks/import").contextPath("/api").with(owner())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Modern","format":"MODERN","rawText":"Deck\\n\\nbad line"}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("IMPORT_INVALID_LINE"))
                .andExpect(jsonPath("$.field").value("rawText"))
                .andExpect(jsonPath("$.line").value(3));
    }

    @Test void importWithoutCardsOmitsLine() throws Exception {
        var error = assertThrows(RuleViolationException.class,
                () -> new DeckImportParserService().parse("Deck"));
        when(service.importDeck(eq(42L), any())).thenThrow(error);
        mvc.perform(post("/decks/import").with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Modern","format":"MODERN","rawText":"Deck"}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IMPORT_NO_CARDS"))
                .andExpect(jsonPath("$.field").value("rawText"))
                .andExpect(jsonPath("$.line").doesNotExist());
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
                        io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.BoardType.COMPANION, 1, false)), io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.DeckStatus.UNDEFINED, null, List.of());
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
                .content("{\"name\":\"Deck\",\"format\":\"MODERN\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void validationResponseRemainsEnglishWithPortugueseAcceptLanguage() throws Exception {
        mvc.perform(put("/decks/{id}/cards", deckId).with(owner())
                        .header("Accept-Language", "pt-BR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oracleId\":null,\"boardType\":null,\"quantity\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid request fields"))
                .andExpect(jsonPath("$.errors[*].message", org.hamcrest.Matchers.containsInAnyOrder(
                        "Oracle ID is required", "Board type is required", "Quantity must be zero or greater")));
        verifyNoInteractions(service);
    }

}
