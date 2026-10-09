package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.deck.ParsedDeckCard;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleErrorCode;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.BoardType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class DeckImportParserService {
    private static final Pattern HEADER = Pattern.compile("(?i)^(Deck|Maindeck|Sideboard|Commander|Companion|Tokens):?\\s*$");
    private static final Pattern CARD = Pattern.compile("^(\\d+)\\s+(.+?)(?:\\s+\\([a-zA-Z0-9]{2,5}\\)\\s+.*)?$");

    public List<ParsedDeckCard> parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw importError(RuleErrorCode.IMPORT_TEXT_REQUIRED, "Deck import text is required", null);
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
                    case "tokens" -> BoardType.TOKENS;
                    default -> throw new IllegalStateException("Unknown board header");
                };
                continue;
            }
            var card = CARD.matcher(line);
            if (!card.matches()) {
                throw importError(RuleErrorCode.IMPORT_INVALID_LINE, "Invalid deck import line: " + lineNumber, lineNumber);
            }
            int quantity;
            try {
                quantity = Integer.parseInt(card.group(1));
            } catch (NumberFormatException ex) {
                throw importError(RuleErrorCode.IMPORT_INVALID_QUANTITY, "Invalid card quantity at line: " + lineNumber, lineNumber);
            }
            String name = card.group(2).strip();
            if (quantity <= 0 || name.isEmpty()) {
                throw importError(RuleErrorCode.IMPORT_INVALID_QUANTITY, "Invalid card quantity or name at line: " + lineNumber, lineNumber);
            }
            cards.add(new ParsedDeckCard(name, quantity, board));
        }
        if (cards.isEmpty()) throw importError(RuleErrorCode.IMPORT_NO_CARDS, "Deck import must contain cards", null);
        return cards;
    }

    private RuleViolationException importError(RuleErrorCode code, String message, Integer line) {
        return new RuleViolationException(code, message, "rawText", List.of(), line);
    }
}
