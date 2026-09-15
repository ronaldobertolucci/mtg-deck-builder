package io.github.ronaldobertolucci.mtgdeckbuilder.controller;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.user.User;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.DeckService;
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
    public DeckController(DeckService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<DeckResponse> create(@AuthenticationPrincipal User user,
                                              @Valid @RequestBody CreateDeckRequest request) {
        DeckResponse deck = service.create(user.getId(), request);
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
