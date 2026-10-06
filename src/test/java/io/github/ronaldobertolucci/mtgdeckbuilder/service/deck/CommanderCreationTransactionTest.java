package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.CreateDeckRequest;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleErrorCode.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({DeckService.class, DeckImportParserService.class, CommanderValidator.class, CardRuleOverrideService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CommanderCreationTransactionTest {
    @Autowired DeckService service;
    @Autowired DeckRepository decks;
    @Autowired DeckCardRepository cards;
    @MockitoBean CardIntegrationService integration;
    @AfterEach void cleanUp() { decks.deleteAll(); }

    private CardDetailsResponse fixture(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/cards/" + name + ".json")) {
            return tools.jackson.databind.json.JsonMapper.builder().build().readValue(stream, CardDetailsResponse.class);
        }
    }
    @Test void persistsFrontFaceCommanderAndFullColorIdentity() throws Exception {
        var card = fixture("esika");
        when(integration.fetchCardDetails(card.oracleId())).thenReturn(card);
        var result = service.create(42L, new CreateDeckRequest("MDFC", Format.COMMANDER, List.of(card.oracleId())));
        assertThat(result.status()).isEqualTo(DeckStatus.UNDEFINED);
        var loaded = service.get(42L, result.id());
        assertThat(loaded.cards()).hasSize(1);
        assertThat(loaded.cards().getFirst().oracleId()).isEqualTo(card.oracleId());
        assertThat(loaded.cards().getFirst().boardType()).isEqualTo(BoardType.COMMANDER);
        assertThat(loaded.cards().getFirst().quantity()).isEqualTo(1);
        assertThat(decks.count()).isEqualTo(1);
    }
    @Test void rejectedBackFaceAndPairAndUnknownIdentityLeaveNoRows() throws Exception {
        var invasion = fixture("invasion");
        when(integration.fetchCardDetails(invasion.oracleId())).thenReturn(invasion);
        assertRule(List.of(invasion.oracleId()), COMMANDER_NOT_ELIGIBLE);
        var a = fixture("normal"); var b = fixture("jace");
        when(integration.fetchCardDetails(a.oracleId())).thenReturn(a);
        when(integration.fetchCardDetails(b.oracleId())).thenReturn(b);
        assertRule(List.of(a.oracleId(), b.oracleId()), INCOMPATIBLE_COMMANDER_PAIR);
        var unknown = new CardDetailsResponse(a.oracleId(), a.name(), a.typeLine(), a.oracleText(),
                null, a.legalities(), a.keywords());
        when(integration.fetchCardDetails(a.oracleId())).thenReturn(unknown);
        assertRule(List.of(a.oracleId()), COLOR_IDENTITY_UNKNOWN);
    }
    @Test void accessoryLookupFailureAlsoLeavesNoRows() throws Exception {
        var source = fixture("normal"); var printing = UUID.randomUUID();
        var card = new CardDetailsResponse(source.oracleId(), source.name(), source.typeLine(), source.oracleText(),
                source.colorIdentity(), source.legalities(), source.keywords(), null, null, null, List.of(),
                "normal", List.of(new io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.RelatedCard(printing)));
        when(integration.fetchCardDetails(card.oracleId())).thenReturn(card);
        when(integration.resolveCards(any())).thenThrow(new CardManagerUnavailableException("offline", null));
        assertThatThrownBy(() -> service.create(42L, new CreateDeckRequest("Failure", Format.COMMANDER, List.of(card.oracleId()))))
                .isInstanceOf(CardManagerUnavailableException.class);
        assertThat(decks.count()).isZero();
        assertThat(cards.count()).isZero();
    }
    private void assertRule(List<UUID> ids, RuleErrorCode code) {
        assertThatThrownBy(() -> service.create(42L, new CreateDeckRequest("Rejected", Format.COMMANDER, ids)))
                .isInstanceOfSatisfying(RuleViolationException.class, ex -> assertThat(ex.getCode()).isEqualTo(code));
        assertThat(decks.count()).isZero();
        assertThat(cards.count()).isZero();
    }
}
