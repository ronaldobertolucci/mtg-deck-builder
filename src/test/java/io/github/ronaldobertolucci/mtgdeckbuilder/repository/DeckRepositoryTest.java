package io.github.ronaldobertolucci.mtgdeckbuilder.repository;

import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.BoardType;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Deck;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.DeckCard;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.Format;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class DeckRepositoryTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private DeckRepository deckRepository;

    @Autowired
    private DeckCardRepository deckCardRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistsAndInvalidatesAnalysis() {
        Deck deck = deckWithCard();
        var at = java.time.Instant.parse("2026-09-15T12:00:00Z");
        deck.recordAnalysis(io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.DeckStatus.IRREGULAR, at, java.util.List.of("First", "Second"));
        deckRepository.saveAndFlush(deck);
        entityManager.clear();
        Deck reloaded = deckRepository.findById(deck.getId()).orElseThrow();
        assertThat(reloaded.getAnalysisMessages()).containsExactly("First", "Second");
        assertThat(reloaded.getAnalyzedAt()).isEqualTo(at);
        assertThat(reloaded.getStatus()).isEqualTo(io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.DeckStatus.IRREGULAR);
        reloaded.invalidateAnalysis();
        deckRepository.saveAndFlush(reloaded);
        entityManager.clear();
        reloaded = deckRepository.findById(deck.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.DeckStatus.UNDEFINED);
        assertThat(reloaded.getAnalysisMessages()).isEmpty();
        assertThat(reloaded.getAnalyzedAt()).isNull();
    }

    @Test
    void saveDeckPersistsCardsInCascade() {
        Deck deck = deckWithCard();
        DeckCard card = deck.getCards().getFirst();
        deckRepository.saveAndFlush(deck);
        entityManager.clear();

        Deck reloaded = deckRepository.findById(deck.getId()).orElseThrow();
        assertThat(reloaded.getCards()).hasSize(1);
        assertThat(reloaded.getCards().getFirst().getId()).isEqualTo(card.getId()).isNotNull();
        assertThat(reloaded.getCards().getFirst().getDeck()).isSameAs(reloaded);
        assertThat(reloaded.getCards().getFirst().getOracleId()).isEqualTo(card.getOracleId());
        assertThat(reloaded.getCards().getFirst().getQuantity()).isEqualTo(4);
        assertThat(reloaded.getCards().getFirst().getBoardType()).isEqualTo(BoardType.MAINBOARD);
        assertThat(reloaded.getFormat()).isEqualTo(Format.MODERN);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void sameOracleInSameDeckAndBoardViolatesUniqueConstraint() {
        Deck deck = deckWithCard();
        deckRepository.saveAndFlush(deck);
        deck.addCard(new DeckCard(deck.getCards().getFirst().getOracleId(), 1, BoardType.MAINBOARD));

        assertThatThrownBy(() -> deckRepository.saveAndFlush(deck))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_deck_cards_deck_oracle_board");
    }

    @Test
    void sameOracleCanAppearInDifferentBoardsAndDecks() {
        Deck first = deckWithCard();
        UUID oracleId = first.getCards().getFirst().getOracleId();
        first.addCard(new DeckCard(oracleId, 1, BoardType.SIDEBOARD));
        Deck second = new Deck(1L, "Second deck", Format.COMMANDER);
        second.addCard(new DeckCard(oracleId, 1, BoardType.COMMANDER));
        deckRepository.saveAndFlush(first);
        deckRepository.saveAndFlush(second);
        entityManager.clear();

        assertThat(deckCardRepository.count()).isEqualTo(3);
    }

    @Test
    void deleteDeckRemovesCardsInJpaCascade() {
        Deck deck = deckRepository.saveAndFlush(deckWithCard());
        UUID cardId = deck.getCards().getFirst().getId();
        entityManager.clear();

        deckRepository.deleteById(deck.getId());
        deckRepository.flush();
        entityManager.clear();

        assertThat(deckRepository.findById(deck.getId())).isEmpty();
        assertThat(deckCardRepository.findById(cardId)).isEmpty();
    }

    @Test
    void deleteDeckDirectlyInDatabaseRemovesCardsInForeignKeyCascade() {
        Deck deck = deckRepository.saveAndFlush(deckWithCard());
        UUID cardId = deck.getCards().getFirst().getId();
        entityManager.clear();

        assertThat(jdbcTemplate.update("DELETE FROM decks WHERE id = ?", deck.getId())).isEqualTo(1);

        assertThat(deckCardRepository.findById(cardId)).isEmpty();
    }

    @Test
    void removingCardFromDeckDeletesOrphan() {
        Deck deck = deckRepository.saveAndFlush(deckWithCard());
        DeckCard card = deck.getCards().getFirst();
        deck.removeCard(card);
        deckRepository.flush();
        entityManager.clear();

        assertThat(deckCardRepository.findById(card.getId())).isEmpty();
        assertThat(deckRepository.findById(deck.getId()).orElseThrow().getCards()).isEmpty();
    }

    @Test
    void findByUserIdPaginatesOnlyThatUsersDecks() {
        deckRepository.save(new Deck(1L, "A", Format.STANDARD));
        deckRepository.save(new Deck(1L, "B", Format.MODERN));
        deckRepository.saveAndFlush(new Deck(2L, "C", Format.LEGACY));
        entityManager.clear();

        var page = deckRepository.findByUserId(1L, PageRequest.of(1, 1, Sort.by("name")));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).extracting(Deck::getName).containsExactly("B");
        assertThat(page.getContent()).extracting(Deck::getUserId).containsExactly(1L);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nonPositiveQuantityViolatesCheckConstraint(int quantity) {
        Deck deck = deckWithCard();
        deck.getCards().getFirst().setQuantity(quantity);

        assertThatThrownBy(() -> deckRepository.saveAndFlush(deck))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("ck_deck_cards_quantity");
    }

    @Test
    void invalidBoardTypeViolatesDatabaseCheckConstraint() {
        Deck deck = deckRepository.saveAndFlush(deckWithCard());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE deck_cards SET board_type = 'INVALID' WHERE deck_id = ?", deck.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("ck_deck_cards_board_type");
    }

    @Test
    void persistsCompanionBoardThroughFlywaySchema() {
        Deck deck = new Deck(1L, "Companion deck", Format.MODERN);
        deck.addCard(new DeckCard(UUID.randomUUID(), 1, BoardType.COMPANION));
        deckRepository.saveAndFlush(deck);
        entityManager.clear();
        assertThat(deckRepository.findById(deck.getId()).orElseThrow().getCards().getFirst().getBoardType())
                .isEqualTo(BoardType.COMPANION);
    }

    @Test
    void databaseRejectsCompanionQuantityGreaterThanOne() {
        Deck deck = new Deck(1L, "Companion deck", Format.MODERN);
        deck.addCard(new DeckCard(UUID.randomUUID(), 2, BoardType.COMPANION));
        assertThatThrownBy(() -> deckRepository.saveAndFlush(deck))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("ck_deck_cards_companion_quantity");
    }

    private Deck deckWithCard() {
        Deck deck = new Deck(1L, "Modern deck", Format.MODERN);
        deck.addCard(new DeckCard(UUID.randomUUID(), 4, BoardType.MAINBOARD));
        return deck;
    }
}
