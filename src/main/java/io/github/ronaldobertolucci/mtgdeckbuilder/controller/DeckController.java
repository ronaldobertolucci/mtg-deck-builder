package io.github.ronaldobertolucci.mtgdeckbuilder.controller;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.DeckService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.DeckStatsService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.ManaSuggestionService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.DeckExportService;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.ExportFormat;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import java.util.UUID;

@RestController
@RequestMapping("/decks")
public class DeckController {
    private final DeckService service;
    private final DeckExportService exportService;
    private final DeckStatsService statsService;
    private final ManaSuggestionService manaSuggestionService;
    public DeckController(DeckService service, DeckExportService exportService, DeckStatsService statsService,
                          ManaSuggestionService manaSuggestionService) {
        this.service = service;
        this.exportService = exportService;
        this.statsService = statsService;
        this.manaSuggestionService = manaSuggestionService;
    }

    @GetMapping("/{deckId}/mana-suggestion")
    public ManaSuggestionResponse suggestManaBase(@AuthenticationPrincipal User user, @PathVariable UUID deckId,
                                                  @RequestParam(defaultValue = "36") int targetLands) {
        return manaSuggestionService.suggestManaBase(deckId, user.getId(), targetLands);
    }

    @GetMapping("/{deckId}/stats")
    public DeckStatsResponse getDeckStats(@AuthenticationPrincipal User user, @PathVariable UUID deckId) {
        return statsService.getDeckStats(deckId, user.getId());
    }

    @GetMapping("/{deckId}/export")
    public ExportDeckResponse exportDeck(@AuthenticationPrincipal User user, @PathVariable UUID deckId,
                                        @RequestParam(defaultValue = "ARENA") ExportFormat format) {
        return exportService.exportDeck(deckId, format, user.getId());
    }

    @PostMapping
    public ResponseEntity<DeckResponse> create(@AuthenticationPrincipal User user,
                                              @Valid @RequestBody CreateDeckRequest request) {
        DeckResponse deck = service.create(user.getId(), request);
        var location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/decks/{id}").buildAndExpand(deck.id()).toUri();
        return ResponseEntity.created(location).body(deck);
    }

    @PostMapping("/import")
    public ResponseEntity<DeckResponse> importDeck(@AuthenticationPrincipal User user,
                                                   @Valid @RequestBody ImportDeckRequest request) {
        DeckResponse deck = service.importDeck(user.getId(), request);
        var location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/decks/{id}").buildAndExpand(deck.id()).toUri();
        return ResponseEntity.created(location).body(deck);
    }

    @PutMapping("/{deckId}/cards")
    public DeckResponse upsertCard(@AuthenticationPrincipal User user, @PathVariable UUID deckId,
                                  @Valid @RequestBody UpsertDeckCardRequest request) {
        return service.upsertCard(user.getId(), deckId, request);
    }
}
