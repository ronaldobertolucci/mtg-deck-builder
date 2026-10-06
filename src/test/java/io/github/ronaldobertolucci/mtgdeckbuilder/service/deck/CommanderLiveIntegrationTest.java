package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.config.CardManagerConfiguration;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.CreateDeckRequest;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in: run only against an isolated database and the real local Card Manager. */
@DataJpaTest(properties = "services.card-manager.url=${COMMANDER_LIVE_TEST_URL}")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({DeckService.class, DeckImportParserService.class, CommanderValidator.class, CardRuleOverrideService.class,
        CardIntegrationService.class, CardManagerConfiguration.class})
@ImportAutoConfiguration({RestClientAutoConfiguration.class, CacheAutoConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "COMMANDER_LIVE_TEST_URL", matches = ".+")
class CommanderLiveIntegrationTest {
    @Autowired DeckService service;
    @Autowired CardIntegrationService integration;
    @Autowired DeckRepository decks;
    @Autowired DeckCardRepository cards;

    @Test void realCatalogCreatesMdfcAndRejectsBackFaceAndPairWithoutPersistingFailures() {
        long owner = 812903L;
        var esika = integration.fetchCardDetailsByName("Esika, God of the Tree // The Prismatic Bridge");
        var invasion = integration.fetchCardDetailsByName("Invasion of Ikoria // Zilortha, Apex of Ikoria");
        var isamaru = integration.fetchCardDetailsByName("Isamaru, Hound of Konda");
        long decksBefore = decks.count(), cardsBefore = cards.count();
        assertThatThrownBy(() -> service.create(owner, new CreateDeckRequest("Rejected", Format.COMMANDER, List.of(invasion.oracleId()))))
                .isInstanceOfSatisfying(RuleViolationException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(RuleErrorCode.COMMANDER_NOT_ELIGIBLE));
        assertThatThrownBy(() -> service.create(owner, new CreateDeckRequest("Rejected pair", Format.COMMANDER,
                List.of(esika.oracleId(), isamaru.oracleId()))))
                .isInstanceOfSatisfying(RuleViolationException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(RuleErrorCode.INCOMPATIBLE_COMMANDER_PAIR));
        assertThat(decks.count()).isEqualTo(decksBefore);
        assertThat(cards.count()).isEqualTo(cardsBefore);
        var created = service.create(owner, new CreateDeckRequest("Live MDFC", Format.COMMANDER, List.of(esika.oracleId())));
        try {
            var loaded = service.get(owner, created.id());
            assertThat(loaded.status()).isEqualTo(DeckStatus.UNDEFINED);
            assertThat(loaded.cards()).anySatisfy(c -> {
                assertThat(c.oracleId()).isEqualTo(esika.oracleId());
                assertThat(c.boardType()).isEqualTo(BoardType.COMMANDER);
                assertThat(c.quantity()).isEqualTo(1);
            });
        } finally {
            service.delete(owner, created.id());
        }
        assertThat(decks.count()).isEqualTo(decksBefore);
        assertThat(cards.count()).isEqualTo(cardsBefore);
    }
}
