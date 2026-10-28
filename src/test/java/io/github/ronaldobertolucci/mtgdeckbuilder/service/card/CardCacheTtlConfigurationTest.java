package io.github.ronaldobertolucci.mtgdeckbuilder.service.card;

import io.github.ronaldobertolucci.mtgdeckbuilder.config.CardManagerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@RestClientTest(value = CardIntegrationService.class, properties = {
        "services.card-manager.url=http://card-manager.test", "CARD_CACHE_TTL=15m"})
@Import(CardManagerConfiguration.class)
@ImportAutoConfiguration(CacheAutoConfiguration.class)
class CardCacheTtlConfigurationTest {
    @Autowired private CacheManager cacheManager;

    @Test
    void configuredTtlAppliesToBothCachesWithoutAccessExpiration() {
        for (String name : List.of("cards", "cards_by_name")) {
            var policy = ((CaffeineCache) cacheManager.getCache(name)).getNativeCache().policy();
            assertThat(policy.expireAfterWrite().orElseThrow().getExpiresAfter()).isEqualTo(Duration.ofMinutes(15));
            assertThat(policy.expireAfterAccess()).isEmpty();
            assertThat(policy.eviction().orElseThrow().getMaximum()).isEqualTo(10_000);
        }
    }
}
