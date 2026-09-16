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

@WebMvcTest(DeckAnalysisController.class)
@Import(SecurityConfigurations.class)
class DeckAnalysisControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation.DeckAnalysisService service;
    @MockitoBean TokenService tokenService;
    @MockitoBean UserRepository users;
    private final UUID deckId = UUID.randomUUID();
    private final UUID oracleId = UUID.randomUUID();

    private RequestPostProcessor owner() {
        User user = new User();
        user.setId(42L);
        return authentication(new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("USER"))));
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.DeckStatus.class)
    void returnsAnalysisWith200(io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.DeckStatus state) throws Exception {
        var at = java.time.Instant.parse("2026-09-15T12:00:00Z");
        when(service.analyze(42L,deckId)).thenReturn(new DeckResponse(deckId,"Test",Format.MODERN,null,null,List.of(),state,at,List.of("Reason")));
        mvc.perform(post("/decks/{id}/analysis",deckId).with(owner()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(state.name()))
            .andExpect(jsonPath("$.analyzedAt").value(at.toString()))
            .andExpect(jsonPath("$.analysisMessages[0]").value("Reason"));
        verify(service).analyze(42L,deckId);
    }
    @Test void notOwnedReturns404() throws Exception {
        when(service.analyze(42L,deckId)).thenThrow(new io.github.ronaldobertolucci.mtgdeckbuilder.exception.DeckNotFoundException());
        mvc.perform(post("/decks/{id}/analysis",deckId).with(owner())).andExpect(status().isNotFound());
    }
    @Test void requiresAuthentication() throws Exception {
        mvc.perform(post("/decks/{id}/analysis",deckId)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
