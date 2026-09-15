package io.github.ronaldobertolucci.mtgdeckbuilder.dto.card;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
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
        List<String> colorIdentity
) {
    public CardDetailsResponse {
        colorIdentity = colorIdentity != null ? List.copyOf(colorIdentity) : List.of();
    }
}
