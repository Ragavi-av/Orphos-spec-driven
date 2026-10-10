package io.orphos.eip.briefing.config;

import io.orphos.eip.briefing.service.BriefingDataService;
import io.orphos.eip.common.money.GraphFxRateSource;
import io.orphos.eip.common.money.MoneyConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FX conversion for the HR and cash-flow totals this service serves.
 *
 * <p>Reads the same {@code FxRate} table widgets-service reads — one rate table
 * per tenant, not one per service, or the same figure could be priced two
 * different ways depending on which endpoint a tile happened to call.</p>
 */
@Configuration
public class MoneyConfig {

    @Bean
    public GraphFxRateSource fxRateSource(BriefingDataService data) {
        return new GraphFxRateSource(data::rowsWithParams);
    }

    @Bean
    public MoneyConverter moneyConverter(GraphFxRateSource source) {
        return new MoneyConverter(source);
    }
}
