package io.github.ronaldobertolucci.mtgdeckbuilder.dto.card;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResolvedCardResponse(UUID id, UUID oracleId, String name, String layout, String typeLine) {
    public static boolean isAccessory(String layout, String typeLine) {
        return "token".equals(layout) || "double_faced_token".equals(layout) || "emblem".equals(layout)
                || (typeLine != null && typeLine.contains("Dungeon"));
    }
    public boolean isAccessory() { return isAccessory(layout, typeLine); }
}
