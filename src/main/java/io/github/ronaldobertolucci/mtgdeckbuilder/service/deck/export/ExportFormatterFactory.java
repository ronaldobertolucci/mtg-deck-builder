package io.github.ronaldobertolucci.mtgdeckbuilder.service.deck.export;

import io.github.ronaldobertolucci.mtgdeckbuilder.exception.RuleViolationException;
import io.github.ronaldobertolucci.mtgdeckbuilder.model.deck.ExportFormat;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class ExportFormatterFactory {
    private final List<DeckExportFormatterStrategy> strategies;

    public ExportFormatterFactory(List<DeckExportFormatterStrategy> strategies) {
        this.strategies = strategies;
    }

    public DeckExportFormatterStrategy getFormatter(ExportFormat format) {
        return strategies.stream().filter(strategy -> strategy.supports(format)).findFirst()
                .orElseThrow(() -> new RuleViolationException("Unsupported export format: " + format));
    }
}
