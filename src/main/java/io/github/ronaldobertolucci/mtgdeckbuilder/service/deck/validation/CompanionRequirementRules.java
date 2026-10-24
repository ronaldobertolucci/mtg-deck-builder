package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.validation;

import io.github.ronaldobertolucci.mtgdeckbuilder.dto.card.CardDetailsResponse;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.*;
import java.util.*;
import java.util.regex.Pattern;

/** Companion conditions apply to the starting deck, including commanders, but not the sideboard. */
final class CompanionRequirementRules {
    private CompanionRequirementRules() {}
    enum Rule { GYRUDA, JEGANTHA, KAHEERA, KERUGA, LURRUS, LUTRI, OBOSH, UMORI, YORION, ZIRDA }
    private static final Map<UUID, Rule> RULES = Map.ofEntries(
            Map.entry(UUID.fromString("25eaa977-ca85-4f8d-8be5-9297a3edb14f"), Rule.GYRUDA),
            Map.entry(UUID.fromString("f0587c55-06c1-4930-a62e-668f37464ce9"), Rule.JEGANTHA),
            Map.entry(UUID.fromString("35428c77-9731-4d4d-8f75-e939a491cf86"), Rule.KAHEERA),
            Map.entry(UUID.fromString("ca5121b2-e6d9-40bc-a2d2-05852c4efbe8"), Rule.KERUGA),
            Map.entry(UUID.fromString("3bc757c1-3adb-4321-8832-8e1cc9e687f7"), Rule.LURRUS),
            Map.entry(UUID.fromString("158a6225-a246-4fd6-aa57-0df8067b4383"), Rule.LUTRI),
            Map.entry(UUID.fromString("6985c8a9-9299-461b-8bef-bd2b2c739c11"), Rule.OBOSH),
            Map.entry(UUID.fromString("913383a1-9c0d-40a1-8c5a-b0a8a95c09a6"), Rule.UMORI),
            Map.entry(UUID.fromString("e15504a7-2b67-4185-b916-172145f10b19"), Rule.YORION),
            Map.entry(UUID.fromString("7b515c56-7b1e-42a8-82b0-1b30201fb162"), Rule.ZIRDA));
    private static final Set<String> TYPES = Set.of("Artifact", "Battle", "Creature", "Enchantment",
            "Instant", "Kindred", "Land", "Planeswalker", "Sorcery", "Tribal");
    private static final Set<String> PERMANENTS = Set.of("Artifact", "Battle", "Creature", "Enchantment", "Land", "Planeswalker");
    // CR 710.2, 712.8a, 715.4, 720.4 and 722.4: use only normal/front characteristics.
    private static final Set<String> FRONT_ONLY = Set.of("modal_dfc", "transform", "flip", "adventure", "omen", "prepare");
    private static final Pattern SYMBOL = Pattern.compile("\\{([^}]+)}");
    // CR 702: these keywords encode an activation cost even without reminder text.
    // Adapt/monstrosity are actions, not abilities; boast/forecast/exhaust have an explicit colon.
    private static final Pattern ACTIVATED = Pattern.compile(
            "(?im)^(?:(?:basic land|[a-z-]*)cycling|equip(?: [a-z-]+){0,2}|fortify|crew|saddle|reconfigure|ninjutsu|commander ninjutsu|"
            + "transmute|transfigure|unearth|scavenge|embalm|eternalize|encore|outlast|level up|"
            + "reinforce|aura swap|craft(?: with [^\\n{]+)?|station)(?=\\s*(?:[0-9{—]|$))");

    static void evaluate(Deck deck, Map<UUID, CardDetailsResponse> details,
                         List<AnalysisReason> violations, List<AnalysisReason> uncertainties) {
        var rows = deck.getCards().stream().filter(c -> c.getBoardType() == BoardType.MAINBOARD
                || c.getBoardType() == BoardType.COMMANDER).toList();
        for (var companion : deck.getCards()) {
            if (companion.getBoardType() != BoardType.COMPANION) continue;
            var rule = RULES.get(companion.getOracleId());
            if (rule == null) {
                uncertainties.add(reason(companion, null, "COMPANION_REQUIREMENTS_NOT_EVALUATED",
                        "This companion is not one of the 10 supported companions; a new validator is required.", false));
                continue;
            }
            if (rule == Rule.YORION) {
                long minimum = deck.getFormat() == Format.COMMANDER ? 120 : 80;
                if (rows.stream().mapToLong(DeckCard::getQuantity).sum() < minimum)
                    violations.add(reason(companion, null, "COMPANION_REQUIREMENT_VIOLATED",
                            "Yorion requires at least " + minimum + " cards in the starting deck.", true));
                continue;
            }
            Set<String> shared = null;
            Map<String, Long> names = new HashMap<>();
            for (var row : rows) {
                var card = details.get(row.getOracleId());
                if (card == null) continue; // The analysis already reports unavailable metadata.
                try {
                    var view = view(card);
                    boolean valid = switch (rule) {
                        case GYRUDA -> manaValue(card) % 2 == 0;
                        case OBOSH -> view.types.contains("Land") || manaValue(card) % 2 == 1;
                        case KERUGA -> view.types.contains("Land") || manaValue(card) >= 3;
                        case LURRUS -> Collections.disjoint(view.types, PERMANENTS) || manaValue(card) <= 2;
                        case JEGANTHA -> uniqueSymbols(view.manaCost);
                        case KAHEERA -> !view.types.contains("Creature") || kaheera(view);
                        case ZIRDA -> Collections.disjoint(view.types, PERMANENTS) || activated(view);
                        case LUTRI -> {
                            if (view.types.contains("Land")) yield true;
                            require(view.name);
                            boolean unique = true;
                            for (String name : view.name.split(" // "))
                                if (names.merge(name, (long) row.getQuantity(), Long::sum) > 1) unique = false;
                            yield unique;
                        }
                        case UMORI -> {
                            if (view.types.contains("Land")) yield true;
                            if (shared == null) shared = new HashSet<>(view.types);
                            else shared.retainAll(view.types);
                            yield !shared.isEmpty();
                        }
                        case YORION -> throw new IllegalStateException("Handled above");
                    };
                    if (!valid) violations.add(reason(companion, row, "COMPANION_REQUIREMENT_VIOLATED",
                            rule + " starting-deck requirement is not satisfied by " + card.name() + ".", true));
                } catch (IncompleteData ex) {
                    uncertainties.add(reason(companion, row, "COMPANION_CARD_DATA_INCOMPLETE",
                            "Missing card characteristics needed to evaluate " + rule + ": " + row.getOracleId(), false));
                }
            }
        }
    }

    private record View(String name, String typeLine, Set<String> types, String text, String manaCost) {}

    private static View view(CardDetailsResponse card) {
        String name = card.name(), type = card.typeLine(), text = card.oracleText(), mana = card.manaCost();
        if (FRONT_ONLY.contains(Objects.toString(card.layout(), ""))) {
            if (card.cardFaces().size() != 2 || card.cardFaces().getFirst() == null) throw new IncompleteData();
            var face = card.cardFaces().getFirst();
            name = face.name(); type = face.typeLine(); text = face.oracleText(); mana = face.manaCost();
        } else if ("split".equals(card.layout()) && !card.cardFaces().isEmpty()) {
            // Split cards have the combined characteristics of both halves outside the stack.
            var costs = new ArrayList<String>();
            var texts = new ArrayList<String>();
            for (var face : card.cardFaces()) {
                if (face == null) throw new IncompleteData();
                costs.add(face.manaCost()); texts.add(face.oracleText());
            }
            if (mana == null && costs.stream().allMatch(Objects::nonNull)) mana = String.join("", costs);
            if (text == null && texts.stream().allMatch(Objects::nonNull)) text = String.join("\n", texts);
        } else if (!card.cardFaces().isEmpty()) {
            // Do not silently combine characteristics for a new, unsupported multiface layout.
            throw new IncompleteData();
        }
        require(type);
        Set<String> types = new HashSet<>();
        for (String half : type.split(" // "))
            for (String token : half.split("—", 2)[0].strip().split("\\s+"))
                if (TYPES.contains(token)) types.add(token.equals("Tribal") ? "Kindred" : token);
        if (types.isEmpty()) throw new IncompleteData();
        return new View(name, type, types, text, mana);
    }

    private static double manaValue(CardDetailsResponse card) {
        if (card.cmc() == null || !Double.isFinite(card.cmc()) || card.cmc() < 0) throw new IncompleteData();
        return card.cmc(); // Catalog cmc is the mana value outside the stack (X = 0).
    }

    private static boolean uniqueSymbols(String cost) {
        if (cost == null) throw new IncompleteData();
        Set<String> symbols = new HashSet<>();
        var matcher = SYMBOL.matcher(cost);
        while (matcher.find()) if (!symbols.add(matcher.group(1))) return false;
        return true;
    }

    private static boolean kaheera(View view) {
        if (Pattern.compile("\\b(Cat|Elemental|Nightmare|Dinosaur|Beast)\\b")
                .matcher(view.typeLine.split("—", 2).length == 2 ? view.typeLine.split("—", 2)[1] : "").find()) return true;
        if (view.text == null) throw new IncompleteData();
        String text = printedAbilities(view.text).replaceAll(" +\\.", ".");
        if (Pattern.compile("(?im)^Changeling\\s*(?:[,.]|$)").matcher(text).find()) return true;
        // Only an unconditional characteristic-defining ability applies in the starting deck.
        // Effects that grant types to a target, tokens, or only on the battlefield do not.
        return text.lines().map(String::strip).anyMatch(line ->
                line.equals("This creature is every creature type.")
                || line.equals("This card is every creature type.")
                || (view.name != null && line.equals(view.name + " is every creature type.")));
    }

    private static boolean activated(View view) {
        // Basic land subtypes intrinsically grant activated mana abilities, including on nonbasic lands.
        if (view.types.contains("Land") && Pattern.compile("\\b(Plains|Island|Swamp|Mountain|Forest)\\b")
                .matcher(view.typeLine).find()) return true;
        if (view.text == null) throw new IncompleteData();
        String text = printedAbilities(view.text);
        return text.contains(":") || ACTIVATED.matcher(text).find();
    }

    private static String printedAbilities(String text) {
        // Reminder text and quoted abilities granted to other objects are not abilities of this card.
        return text.replaceAll("(?s)\\([^)]*\\)", "")
                .replaceAll("[\"“][^\"”]*[\"”]", "");
    }

    private static void require(String value) {
        if (value == null || value.isBlank()) throw new IncompleteData();
    }
    private static final class IncompleteData extends RuntimeException {}

    private static AnalysisReason reason(DeckCard companion, DeckCard card, String code, String message, boolean violation) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("companionOracleId", companion.getOracleId().toString());
        if (card != null) parameters.put("oracleId", card.getOracleId().toString());
        return new AnalysisReason(code, violation ? AnalysisReason.Severity.VIOLATION : AnalysisReason.Severity.UNCERTAINTY,
                message, parameters);
    }
}
