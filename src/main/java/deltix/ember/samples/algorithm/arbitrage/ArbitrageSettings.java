package deltix.ember.samples.algorithm.arbitrage;

import deltix.anvil.util.annotation.Alphanumeric;

public class ArbitrageSettings {
    private long entryExchange;
    private long exitExchange;

    public @Alphanumeric long getEntryExchange() {
        return entryExchange;
    }

    public void setEntryExchange(@Alphanumeric long entryExchange) {
        this.entryExchange = entryExchange;
    }

    public @Alphanumeric long getExitExchange() {
        return exitExchange;
    }

    public void setExitExchange(@Alphanumeric long exitExchange) {
        this.exitExchange = exitExchange;
    }
}
