package io.github.ronaldobertolucci.mtgdeckbuilder.dto.card;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CardFaceResponse(String name, @JsonProperty("type_line") String typeLine,
                               @JsonProperty("oracle_text") String oracleText) {}
