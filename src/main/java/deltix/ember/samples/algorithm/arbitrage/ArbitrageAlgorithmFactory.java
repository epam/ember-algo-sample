package deltix.ember.samples.algorithm.arbitrage;

import deltix.anvil.util.annotation.Alphanumeric;
import deltix.anvil.util.annotation.Required;
import deltix.ember.service.algorithm.AbstractAlgorithmFactory;
import deltix.ember.service.algorithm.AlgorithmContext;

/**
 * Arbitrage Algorithm Factory.
 * Here is an example of algorithm config in ember.conf file:
 * algorithms {
 *   ARB: ${template.algorithm.default} {
 *     factory = "deltix.ember.samples.algorithm.arbitrage.ArbitrageAlgorithmFactory"
 *     subscription {
 *       streams = [ "OKEX", "BINANCE"]
 *       symbols = [ "BTCUSD" ]
 *     }
 *     settings {
 *       entryExchange = "COINBASE"
 *       exitExchange = "BINANCE"
 *     }
 *   }
 * }
 */
public class ArbitrageAlgorithmFactory extends AbstractAlgorithmFactory {

    @Required
    @Alphanumeric
    private long entryExchange;

    @Required
    @Alphanumeric
    private long exitExchange;

    public ArbitrageAlgorithmFactory() {
        setOrderCacheCapacity(1000);
        setMaxInactiveOrdersCacheSize(1000);
        setInitialActiveOrdersCacheSize(1000);
        setInitialClientsCapacity(16);
    }

    public void setEntryExchange(@Alphanumeric long entryExchange) {
        this.entryExchange = entryExchange;
    }

    public void setExitExchange(@Alphanumeric long exitExchange) {
        this.exitExchange = exitExchange;
    }

    @Override
    public ArbitrageAlgorithm create(AlgorithmContext context) {
        ArbitrageSettings settings = new ArbitrageSettings();
        settings.setEntryExchange(entryExchange);
        settings.setExitExchange(exitExchange);

        return new ArbitrageAlgorithm(context, getCacheSettings(), settings);
    }
}
