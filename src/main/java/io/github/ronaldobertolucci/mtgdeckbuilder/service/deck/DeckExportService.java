package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.DeckNotFoundException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.DeckRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.CardIntegrationService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.export.ExportFormatterFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class DeckExportService {
    private final DeckRepository repository;
    private final CardIntegrationService integration;
    private final ExportFormatterFactory factory;

    public DeckExportService(DeckRepository repository, CardIntegrationService integration,
                             ExportFormatterFactory factory) {
        this.repository = repository;
        this.integration = integration;
        this.factory = factory;
    }

    @Transactional(readOnly = true)
    public ExportDeckResponse exportDeck(UUID deckId, ExportFormat format, Long userId) {
        Deck deck = repository.findByIdAndUserId(deckId, userId).orElseThrow(DeckNotFoundException::new);
        var formatter = factory.getFormatter(format);
        Map<BoardType, List<ExportableCard>> cardsByZone = new EnumMap<>(BoardType.class);
        for (DeckCard card : deck.getCards()) {
            var details = integration.fetchCardDetails(card.getOracleId());
            cardsByZone.computeIfAbsent(card.getBoardType(), zone -> new ArrayList<>())
                    .add(new ExportableCard(details.name(), card.getQuantity()));
        }
        // Stable output regardless of database collection ordering.
        cardsByZone.values().forEach(cards -> cards.sort(Comparator.comparing(ExportableCard::name)));
        return new ExportDeckResponse(formatter.format(deck.getName(), cardsByZone));
    }
}
