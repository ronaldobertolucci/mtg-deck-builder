package io.github.ronaldobertolucci.mtgdeckbuilder.dto.card;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CardDetailsResponse(
        @JsonProperty("oracle_id")
        UUID oracleId,
        String name,
        @JsonProperty("type_line")
        String typeLine,
        @JsonProperty("oracle_text")
        String oracleText,
        @JsonProperty("color_identity")
        List<String> colorIdentity,
        Map<String, CardLegality> legalities,
        List<String> keywords,
        Double cmc,
        @JsonProperty("mana_cost")
        String manaCost,
        String rarity
) {
    public CardDetailsResponse(UUID oracleId, String name, String typeLine, String oracleText,
                               List<String> colorIdentity, Map<String, CardLegality> legalities,
                               List<String> keywords) {
        this(oracleId, name, typeLine, oracleText, colorIdentity, legalities, keywords, null, null, null);
    }

    public CardDetailsResponse {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        legalities = legalities == null ? Map.of() : legalities.entrySet().stream().collect(
                Collectors.toUnmodifiableMap(entry -> entry.getKey().toLowerCase(Locale.ROOT),
                        entry -> Objects.requireNonNullElse(entry.getValue(), CardLegality.UNKNOWN)));
        colorIdentity = colorIdentity != null ? List.copyOf(colorIdentity) : List.of();
    }
}
