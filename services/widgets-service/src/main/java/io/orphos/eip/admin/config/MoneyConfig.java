package io.orphos.eip.admin.config;

import io.orphos.eip.common.money.GraphFxRateSource;
import io.orphos.eip.common.money.MoneyConverter;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FX conversion for widget values.
 *
 * <p>The rate table lives in the same graph as the observations it prices, so the
 * source is handed this service's existing {@link ArcadeDbTemplate} as a plain
 * query function — see {@link GraphFxRateSource} for why the lambda rather than a
 * direct dependency.</p>
 */
@Configuration
public class MoneyConfig {

    @Bean
    public GraphFxRateSource fxRateSource(ArcadeDbTemplate arcade) {
        return new GraphFxRateSource(arcade::query);
    }

    @Bean
    public MoneyConverter moneyConverter(GraphFxRateSource source) {
        return new MoneyConverter(source);
    }
}
