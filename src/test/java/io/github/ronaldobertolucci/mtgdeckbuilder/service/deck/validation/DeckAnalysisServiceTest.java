package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;
import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import io.github.ronaldobertolucci.mtgdeckbuilder.repository.DeckRepository;
import io.github.ronaldobertolucci.mtgdeckbuilder.service.card.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class DeckAnalysisServiceTest {
    final DeckRepository repository = mock(DeckRepository.class);
    final CardIntegrationService integration = mock(CardIntegrationService.class);
    final DeckAnalysisService service = new DeckAnalysisService(repository, integration, new CardRuleOverrideService());
    final UUID deckId = UUID.randomUUID();
    Deck deck;
    @BeforeEach void setup() {
        deck = new Deck(42L, "Test", Format.MODERN);
        when(repository.findOwnedForUpdate(deckId, 42L)).thenAnswer(i -> Optional.of(deck));
        when(repository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }
    UUID add(int qty, BoardType board, String type, String text, CardLegality legality, List<String> colors, List<String> keywords) {
        UUID id = UUID.randomUUID();
        deck.addCard(new DeckCard(id, qty, board));
        when(integration.refreshCardDetails(id)).thenReturn(new CardDetailsResponse(id, "Card", type, text, colors,
            Map.of(deck.getFormat().name().toLowerCase(Locale.ROOT), legality), keywords));
        return id;
    }
    UUID basic(int qty, BoardType board) { return add(qty, board, "Basic Land — Island", "", CardLegality.LEGAL, List.of(), List.of()); }
    void expect(DeckStatus status) {
        var result = service.analyze(42L, deckId);
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.analyzedAt()).isNotNull();
        assertThat(deck.getStatus()).isEqualTo(status);
        if (status != DeckStatus.REGULAR) assertThat(result.analysisMessages()).isNotEmpty();
        verify(integration, never()).fetchCardDetails(any());
    }
    @ParameterizedTest @EnumSource(value=Format.class, names={"STANDARD","MODERN","PIONEER","LEGACY"})
    void constructedMinimumAndNoMaximum(Format format) { deck.setFormat(format); basic(61, BoardType.MAINBOARD); expect(DeckStatus.REGULAR); }
    @Test void insufficientMain() { basic(59, BoardType.MAINBOARD); expect(DeckStatus.IRREGULAR); }
    @Test void sideLimit() { basic(60, BoardType.MAINBOARD); basic(16, BoardType.SIDEBOARD); expect(DeckStatus.IRREGULAR); }
    @Test void sideBoundary() { basic(60, BoardType.MAINBOARD); basic(15, BoardType.SIDEBOARD); expect(DeckStatus.REGULAR); }
    @ParameterizedTest @EnumSource(value=CardLegality.class, names={"BANNED","NOT_LEGAL"})
    void changedLegality(CardLegality legality) { basic(60, BoardType.MAINBOARD); add(1, BoardType.MAINBOARD,"Creature", "", legality,List.of(),List.of()); expect(DeckStatus.IRREGULAR); }
    @Test void restrictedOverridesAnyNumber() { add(60,BoardType.MAINBOARD,"Creature","A deck can have any number of cards named Card.",CardLegality.RESTRICTED,List.of(),List.of()); expect(DeckStatus.IRREGULAR); }
    @Test void restrictedSingleAllowed() { basic(60,BoardType.MAINBOARD); add(1,BoardType.SIDEBOARD,"Creature","",CardLegality.RESTRICTED,List.of(),List.of()); expect(DeckStatus.REGULAR); }
    @Test void anyNumber() { add(60,BoardType.MAINBOARD,"Creature","A deck can have any number of cards named Card.",CardLegality.LEGAL,List.of(),List.of()); expect(DeckStatus.REGULAR); }
    @Test void copiesAcrossZonesAndSingleFetch() {
        basic(60, BoardType.MAINBOARD);
        UUID id=add(4,BoardType.MAINBOARD,"Creature","",CardLegality.LEGAL,List.of(),List.of());
        deck.addCard(new DeckCard(id,1,BoardType.SIDEBOARD)); expect(DeckStatus.IRREGULAR);
        verify(integration,times(1)).refreshCardDetails(id);
    }
    @Test void numericOverride() { basic(60,BoardType.MAINBOARD); add(7,BoardType.SIDEBOARD,"Creature","A deck can have up to seven cards named Card.",CardLegality.LEGAL,List.of(),List.of()); expect(DeckStatus.REGULAR); }
    @Test void unknownLegality() { add(60,BoardType.MAINBOARD,"Basic Land","",CardLegality.UNKNOWN,List.of(),List.of()); expect(DeckStatus.UNDEFINED); }
    @Test void outage() { UUID id=basic(60,BoardType.MAINBOARD); when(integration.refreshCardDetails(id)).thenThrow(new CardManagerUnavailableException("offline",null)); expect(DeckStatus.UNDEFINED); }
    @Test void missingCard() { UUID id=basic(60,BoardType.MAINBOARD); when(integration.refreshCardDetails(id)).thenThrow(new CardNotFoundException(id,null)); expect(DeckStatus.UNDEFINED); }
    @Test void companionUndefined() { basic(60,BoardType.MAINBOARD); companion(); expect(DeckStatus.UNDEFINED); }
    void companion() { add(1,BoardType.COMPANION,"Creature","",CardLegality.LEGAL,List.of(),List.of("Companion")); }
    @Test void knownViolationTakesPrecedence() { basic(60,BoardType.MAINBOARD); basic(15,BoardType.SIDEBOARD); companion(); expect(DeckStatus.IRREGULAR); }
    void commander(int main, boolean pair) {
        deck.setFormat(Format.COMMANDER);
        add(1,BoardType.COMMANDER,"Legendary Creature — Elf","Partner",CardLegality.LEGAL,List.of("U"),List.of());
        if(pair) add(1,BoardType.COMMANDER,"Legendary Creature — Human","Partner",CardLegality.LEGAL,List.of("G"),List.of());
        basic(main,BoardType.MAINBOARD);
    }
    @Test void regularCommander() { commander(99,false); expect(DeckStatus.REGULAR); }
    @Test void regularPair() { commander(98,true); expect(DeckStatus.REGULAR); }
    @Test void commanderIncomplete() { commander(98,false); expect(DeckStatus.IRREGULAR); }
    @Test void commanderTooLarge() { commander(100,false); expect(DeckStatus.IRREGULAR); }
    @Test void commanderSideboard() { commander(99,false); basic(1,BoardType.SIDEBOARD); expect(DeckStatus.IRREGULAR); }
    @Test void commanderCompanionOutsideHundred() { commander(99,false); companion(); expect(DeckStatus.UNDEFINED); }
    @Test void wrongIdentity() { commander(98,false); add(1,BoardType.MAINBOARD,"Creature","",CardLegality.LEGAL,List.of("R"),List.of()); expect(DeckStatus.IRREGULAR); }
    @Test void singleton() { commander(97,false); add(2,BoardType.MAINBOARD,"Creature","",CardLegality.LEGAL,List.of(),List.of()); expect(DeckStatus.IRREGULAR); }
    @Test void invalidPair() {
        commander(98,false);
        add(1,BoardType.COMMANDER,"Legendary Creature — Elf","",CardLegality.LEGAL,List.of("G"),List.of());
        expect(DeckStatus.IRREGULAR);
    }
    @Test void bannedCommander() {
        deck.setFormat(Format.COMMANDER);
        add(1,BoardType.COMMANDER,"Legendary Creature — Elf","",CardLegality.BANNED,List.of(),List.of());
        basic(99,BoardType.MAINBOARD); expect(DeckStatus.IRREGULAR);
    }
    @Test void ordinaryCardInCompanionZone() {
        basic(60,BoardType.MAINBOARD); basic(1,BoardType.COMPANION); expect(DeckStatus.IRREGULAR);
    }
    @Test void unknownMetadataDoesNotHideKnownViolation() {
        UUID id=basic(59,BoardType.MAINBOARD);
        when(integration.refreshCardDetails(id)).thenThrow(new CardManagerUnavailableException("offline",null));
        expect(DeckStatus.IRREGULAR);
    }
    @Test void ordinaryCopyLimitBoundary() {
        basic(60,BoardType.MAINBOARD);
        UUID id=add(3,BoardType.MAINBOARD,"Creature","",CardLegality.LEGAL,List.of(),List.of());
        deck.addCard(new DeckCard(id,1,BoardType.SIDEBOARD)); expect(DeckStatus.REGULAR);
    }
    @Test void absentCommander() { deck.setFormat(Format.COMMANDER); basic(100,BoardType.MAINBOARD); expect(DeckStatus.IRREGULAR); }
    @Test void anotherOwner() { assertThatThrownBy(() -> service.analyze(99L,deckId)).isInstanceOf(DeckNotFoundException.class); verifyNoInteractions(integration); }
    @Test void reanalysisReplacesMessages() { basic(59,BoardType.MAINBOARD); expect(DeckStatus.IRREGULAR); basic(1,BoardType.MAINBOARD); expect(DeckStatus.REGULAR); assertThat(deck.getAnalysisMessages()).isEmpty(); }
}
