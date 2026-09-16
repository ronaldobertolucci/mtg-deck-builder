package io.github.ronaldobertolucci.mtgdeckbuilder.controller;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.DeckResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation.DeckAnalysisService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/decks")
public class DeckAnalysisController {
    private final DeckAnalysisService service;
    public DeckAnalysisController(DeckAnalysisService service) { this.service = service; }

    @PostMapping("/{deckId}/analysis")
    public DeckResponse analyze(@AuthenticationPrincipal User user, @PathVariable UUID deckId) {
        return service.analyze(user.getId(), deckId);
    }
}
