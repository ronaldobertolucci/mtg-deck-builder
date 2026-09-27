package io.github.ronaldobertolucci.mtgdeckbuilder.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import java.net.http.HttpClient;
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
    public ClientHttpRequestFactoryBuilder<?> clientHttpRequestFactoryBuilder() {
        // Uvicorn does not support the JDK client's cleartext HTTP/2 upgrade (h2c).
        // Let Boot apply the configured connection/read timeouts to this builder.
        return ClientHttpRequestFactoryBuilder.jdk()
                .withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1));
    }

    @Bean
    public RestClient cardManagerRestClient(RestClient.Builder builder,
            CardManagerProperties properties) {
        if (StringUtils.hasText(properties.url())) {
            builder.baseUrl(properties.url());
        }
        return builder.build();
    }
}
