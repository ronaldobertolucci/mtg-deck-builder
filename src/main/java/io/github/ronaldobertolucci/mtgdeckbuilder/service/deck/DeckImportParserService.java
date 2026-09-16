package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.ParsedDeckCard;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.BoardType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class DeckImportParserService {
    private static final Pattern HEADER = Pattern.compile("(?i)^(Deck|Maindeck|Sideboard|Commander|Companion):?\\s*$");
    private static final Pattern CARD = Pattern.compile("^(\\d+)\\s+(.+?)(?:\\s+\\([a-zA-Z0-9]{2,5}\\)\\s+.*)?$");

    public List<ParsedDeckCard> parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new RuleViolationException("Deck import text is required");
        }
        List<ParsedDeckCard> cards = new ArrayList<>();
        BoardType board = BoardType.MAINBOARD;
        int lineNumber = 0;
        for (String rawLine : rawText.split("\\R")) {
            lineNumber++;
            String line = rawLine.strip();
            if (line.isEmpty()) continue;
            var header = HEADER.matcher(line);
            if (header.matches()) {
                board = switch (header.group(1).toLowerCase(Locale.ROOT)) {
                    case "deck", "maindeck" -> BoardType.MAINBOARD;
                    case "sideboard" -> BoardType.SIDEBOARD;
                    case "commander" -> BoardType.COMMANDER;
                    case "companion" -> BoardType.COMPANION;
                    default -> throw new IllegalStateException("Unknown board header");
                };
                continue;
            }
            var card = CARD.matcher(line);
            if (!card.matches()) {
                throw new RuleViolationException("Invalid deck import line: " + lineNumber);
            }
            int quantity;
            try {
                quantity = Integer.parseInt(card.group(1));
            } catch (NumberFormatException ex) {
                throw new RuleViolationException("Invalid card quantity at line: " + lineNumber);
            }
            String name = card.group(2).strip();
            if (quantity <= 0 || name.isEmpty()) {
                throw new RuleViolationException("Invalid card quantity or name at line: " + lineNumber);
            }
            cards.add(new ParsedDeckCard(name, quantity, board));
        }
        if (cards.isEmpty()) throw new RuleViolationException("Deck import must contain cards");
        return cards;
    }
}
