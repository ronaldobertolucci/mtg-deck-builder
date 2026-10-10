package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.PrintDeckCardResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.DeckRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.CardIntegrationService;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.export.ExportFormatterFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(DeckExportService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PrintDeckCompositionTransactionTest {
    @Autowired DeckExportService service;
    @MockitoSpyBean DeckRepository decks;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean CardIntegrationService integration;
    @MockitoBean ExportFormatterFactory factory;

    @AfterEach void cleanUp() { decks.deleteAll(); }

    @Test void emptyDeckHasRevisionAndMetadataUpdatePreservesIt() {
        Deck deck = decks.saveAndFlush(new Deck(42L, "Empty", Format.MODERN));
        var before = service.printCards(deck.getId(), 42L);
        assertThat(before.cards()).isEmpty();
        assertThat(before.compositionRevision())
                .isEqualTo("v1:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var saved = decks.findOwnedForUpdate(deck.getId(), 42L).orElseThrow();
            saved.setName("Renamed");
            saved.setFormat(Format.LEGACY);
        });

        assertThat(decks.findById(deck.getId()).orElseThrow().getUpdatedAt()).isAfter(deck.getUpdatedAt());
        assertThat(service.printCards(deck.getId(), 42L)).isEqualTo(before);
        verifyNoInteractions(integration, factory);
    }

    @Test void concurrentCommittedEditKeepsCardsAndRevisionOnSameReadSnapshot() throws Exception {
        UUID sourceId = UUID.randomUUID(), otherId = UUID.randomUUID(), tokenId = UUID.randomUUID();
        UUID replacementId = UUID.randomUUID(), replacementTokenId = UUID.randomUUID();
        Deck deck = new Deck(42L, "Deck", Format.MODERN);
        deck.addCard(new DeckCard(sourceId, 2, BoardType.MAINBOARD));
        deck.addCard(new DeckCard(otherId, 3, BoardType.SIDEBOARD));
        deck.addCard(DeckCard.generatedToken(tokenId));
        UUID deckId = decks.saveAndFlush(deck).getId();
        var before = service.printCards(deckId, 42L);
        var cardsRead = new CountDownLatch(1);
        var editCommitted = new CountDownLatch(1);
        var pauseFirstRead = new AtomicBoolean(true);
        var repositoryAnswer = mockingDetails(decks).getMockCreationSettings().getDefaultAnswer();

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            var result = (Optional<Deck>) repositoryAnswer.answer(invocation);
            if (pauseFirstRead.compareAndSet(true, false)) {
                // Materialize the actual database collection in the reader's transaction.
                assertThat(result.orElseThrow().getCards()).hasSize(3);
                cardsRead.countDown();
                assertThat(editCommitted.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(decks).findByIdAndUserId(deckId, 42L);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var reading = executor.submit(() -> service.printCards(deckId, 42L));
            try {
                if (!cardsRead.await(10, TimeUnit.SECONDS)) {
                    reading.get(1, TimeUnit.SECONDS);
                    fail("Reader did not reach the composition snapshot");
                }
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    var editing = decks.findOwnedForUpdate(deckId, 42L).orElseThrow();
                    for (var card : List.copyOf(editing.getCards())) {
                        if (card.getOracleId().equals(otherId)) card.setQuantity(4);
                        else editing.removeCard(card);
                    }
                    editing.addCard(new DeckCard(replacementId, 2, BoardType.MAINBOARD));
                    var token = DeckCard.generatedToken(replacementTokenId);
                    token.setQuantity(2);
                    editing.addCard(token);
                });
            } finally {
                editCommitted.countDown();
            }

            // The edit committed before the reader hashes and returns its response.
            assertThat(reading.get(10, TimeUnit.SECONDS)).isEqualTo(before);
        }

        var after = service.printCards(deckId, 42L);
        assertThat(after.cards()).containsExactlyInAnyOrder(
                new PrintDeckCardResponse(replacementId, 2),
                new PrintDeckCardResponse(otherId, 4),
                new PrintDeckCardResponse(replacementTokenId, 2));
        assertThat(after.compositionRevision()).isNotEqualTo(before.compositionRevision());
        verifyNoInteractions(integration, factory);
    }
}
