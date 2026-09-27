package io.github.ronaldobertolucci.mtgdeckbuilder.dto.card;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;
@JsonIgnoreProperties(ignoreUnknown = true)
public record RelatedCard(UUID id) {}
