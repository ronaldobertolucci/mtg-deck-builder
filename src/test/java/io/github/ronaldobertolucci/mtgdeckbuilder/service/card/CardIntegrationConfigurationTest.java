package io.github.ronaldobertolucci.mtgdeckbuilder.service.card;

import io.github.ronaldobertolucci.mtgdeckbuilder.config.CardManagerConfiguration;
import io.github.ronaldobertolucci.mtgdeckbuilder.config.CardManagerProperties;
import io.github.ronaldobertolucci.mtgdeckbuilder.exception.CardManagerUnavailableException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardIntegrationConfigurationTest {

    @ParameterizedTest
    @MethodSource("incompleteConfigurations")
    void missingOracleLookupConfigurationFailsWithoutHttpRequest(CardManagerProperties properties) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = new CardManagerConfiguration().cardManagerRestClient(builder, properties);
        CardIntegrationService service = new CardIntegrationService(client, properties);

        assertThatThrownBy(() -> service.fetchCardDetails(UUID.randomUUID()))
                .isInstanceOf(CardManagerUnavailableException.class)
                .hasMessageContaining("oracle lookup is not configured");
        server.verify();
    }

    private static Stream<CardManagerProperties> incompleteConfigurations() {
        return Stream.of(
                new CardManagerProperties(null, null),
                new CardManagerProperties("", ""),
                new CardManagerProperties("http://card-manager.test", ""),
                new CardManagerProperties("", "/stub/cards/{oracleId}"),
                new CardManagerProperties("http://card-manager.test", "/stub/cards")
        );
    }
}
