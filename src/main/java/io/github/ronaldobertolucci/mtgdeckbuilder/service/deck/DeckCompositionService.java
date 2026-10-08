package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.DeckCardResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.DeckCompositionResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.DeckNotFoundException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.BoardType;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.DeckRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.CardIntegrationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DeckCompositionService {
    private final DeckRepository repository;
    private final CardIntegrationService integration;

    public DeckCompositionService(DeckRepository repository, CardIntegrationService integration) {
        this.repository = repository;
        this.integration = integration;
    }

    @Transactional(readOnly = true)
    public DeckCompositionResponse getDeckComposition(UUID deckId, Long userId) {
        var deck = repository.findByIdAndUserId(deckId, userId).orElseThrow(DeckNotFoundException::new);
        Map<BoardType, Map<String, List<DeckCardResponse>>> boards = new LinkedHashMap<>();
        for (var board : BoardType.values()) {
            Map<String, List<DeckCardResponse>> types = new LinkedHashMap<>();
            DeckCardTypeClassifier.TYPES.forEach(type -> types.put(type, new ArrayList<>()));
            DeckCardTypeClassifier.ACCESSORY_TYPES.forEach(type -> types.put(type, new ArrayList<>()));
            boards.put(board, types);
        }
        Map<UUID, CardDetailsResponse> detailsByOracleId = new HashMap<>();
        for (var card : deck.getCards()) {
            var details = detailsByOracleId.computeIfAbsent(card.getOracleId(), integration::fetchCardDetails);
            String type = DeckCardTypeClassifier.compositionType(details);
            boards.get(card.getBoardType()).get(type).add(DeckCardResponse.from(card));
        }
        return new DeckCompositionResponse(boards);
    }
}
