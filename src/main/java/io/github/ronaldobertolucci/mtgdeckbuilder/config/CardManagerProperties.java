package io.github.ronaldobertolucci.mtgdeckbuilder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "services.card-manager")
public record CardManagerProperties(String url, String oracleDetailsPath) {

    public boolean isOracleLookupConfigured() {
        return StringUtils.hasText(url)
                && StringUtils.hasText(oracleDetailsPath)
                && oracleDetailsPath.contains("{oracleId}");
    }
}
