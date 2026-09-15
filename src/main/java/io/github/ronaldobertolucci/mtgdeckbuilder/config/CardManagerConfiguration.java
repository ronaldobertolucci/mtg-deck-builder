package io.github.ronaldobertolucci.mtgdeckbuilder.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@EnableCaching
@EnableConfigurationProperties(CardManagerProperties.class)
public class CardManagerConfiguration {

    @Bean
    public RestClient cardManagerRestClient(RestClient.Builder builder,
            CardManagerProperties properties) {
        if (StringUtils.hasText(properties.url())) {
            builder.baseUrl(properties.url());
        }
        return builder.build();
    }
}
