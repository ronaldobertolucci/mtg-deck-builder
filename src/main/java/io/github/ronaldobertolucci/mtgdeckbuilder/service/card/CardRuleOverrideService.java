package io.github.ronaldobertolucci.mtgdeckbuilder.service.card;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class CardRuleOverrideService {

    private static final Pattern BASIC = Pattern.compile("\\bBasic\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNLIMITED = Pattern.compile(
            "\\bA\\s+deck\\s+can\\s+have\\s+any\\s+number\\s+of\\s+cards\\s+named\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMITED = Pattern.compile(
            "\\bA\\s+deck\\s+can\\s+have\\s+up\\s+to\\s+([a-z0-9]+(?:[\\s-]+[a-z]+)?)\\s+cards\\s+named\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Map<String, Integer> NUMBERS = Map.ofEntries(
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3),
            Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6),
            Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9),
            Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12),
            Map.entry("thirteen", 13), Map.entry("fourteen", 14), Map.entry("fifteen", 15),
            Map.entry("sixteen", 16), Map.entry("seventeen", 17), Map.entry("eighteen", 18),
            Map.entry("nineteen", 19), Map.entry("twenty", 20), Map.entry("thirty", 30),
            Map.entry("forty", 40), Map.entry("fifty", 50), Map.entry("sixty", 60),
            Map.entry("seventy", 70), Map.entry("eighty", 80), Map.entry("ninety", 90));

    public int getMaxCopies(CardDetailsResponse card) {
        return getMaxCopies(card, 4);
    }

    // The format supplies its base limit; an explicit "up to four" still overrides singleton.
    public int getMaxCopies(CardDetailsResponse card, int baseLimit) {
        if (card.typeLine() != null && BASIC.matcher(card.typeLine()).find()) {
            return Integer.MAX_VALUE;
        }
        String text = card.oracleText();
        if (text == null) {
            return baseLimit;
        }
        if (UNLIMITED.matcher(text).find()) {
            return Integer.MAX_VALUE;
        }
        var match = LIMITED.matcher(text);
        if (match.find()) {
            int limit = parseNumber(match.group(1));
            if (limit > 0) {
                return limit;
            }
        }
        return baseLimit;
    }

    private int parseNumber(String word) {
        String normalized = word.toLowerCase(Locale.ROOT);
        Integer number = NUMBERS.get(normalized);
        if (number != null) {
            return number;
        }
        String[] parts = normalized.split("[\\s-]+");
        if (parts.length == 2) {
            int tens = NUMBERS.getOrDefault(parts[0], 0);
            int units = NUMBERS.getOrDefault(parts[1], 0);
            if (tens >= 20 && tens % 10 == 0 && units >= 1 && units <= 9) {
                return tens + units;
            }
        }
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}
