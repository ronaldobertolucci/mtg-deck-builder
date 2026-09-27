package io.github.ronaldobertolucci.mtgdeckbuilder.service.card;

import com.sun.net.httpserver.HttpServer;
import io.github.ronaldobertolucci.mtgdeckbuilder.config.CardManagerConfiguration;
import io.github.ronaldobertolucci.mtgdeckbuilder.config.CardManagerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CardManagerHttpTransportTest {
    @Test
    void actualPostUsesHttp11WithoutUpgradeAndResolvesJson() throws Exception {
        UUID id = UUID.randomUUID(), oracle = UUID.randomUUID();
        var protocol = new AtomicReference<String>();
        var upgrade = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cards/resolve", exchange -> {
            protocol.set(exchange.getProtocol());
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("""
                    [{"id":"%s","oracleId":"%s","name":"Treasure","layout":"token","typeLine":"Token Artifact"}]
                    """).formatted(id, oracle).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
                            org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration.class))
                    .withUserConfiguration(CardManagerConfiguration.class)
                    .withPropertyValues("services.card-manager.url=http://127.0.0.1:" + server.getAddress().getPort(),
                            "spring.http.clients.connect-timeout=5s", "spring.http.clients.read-timeout=15s")
                    .run(context -> {
                        var integration = new CardIntegrationService(context.getBean("cardManagerRestClient", RestClient.class),
                                context.getBean(CardManagerProperties.class));
                        assertThat(integration.resolveCards(List.of(id))).singleElement()
                                .satisfies(card -> assertThat(card.oracleId()).isEqualTo(oracle));
                        assertThat(protocol.get()).isEqualTo("HTTP/1.1");
                        assertThat(upgrade.get()).isNull();
                        assertThat(body.get()).contains(id.toString(), "ids");
                    });
        } finally { server.stop(0); }
    }
}
