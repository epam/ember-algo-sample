package deltix.ember.samples.algorithm.marketmaker;

import deltix.anvil.util.annotation.Alphanumeric;
import deltix.anvil.util.annotation.Optional;
import deltix.anvil.util.annotation.Required;
import deltix.ember.service.algorithm.AbstractAlgorithmFactory;
import deltix.ember.service.algorithm.AlgorithmContext;

import java.util.List;

/**
 * Market Maker Algorithm Factory.
 * Here is an example of algorithm config in ember.conf file:
 * algorithms {
 *   MM: ${template.algorithm.default} {
 *     factory = "deltix.ember.samples.algorithm.marketmaker.MarketMakerFactory"
 *     subscription {
 *       streams = [ "COINBASE", "BINANCE"]
 *       symbols = [ "BTCUSD" ]
 *     }
 *     settings {
 *       exchange = "COINBASE"
 *       sourceExchange = "BINANCE"
 *       buyQuoteSizes = [1, 2]
 *       sellQuoteSizes = [1, 2]
 *       buyMargins = [0.01, 0.02]
 *       sellMargins = [0.01, 0.02]
 *       minSpread = 0.01
 *       minPriceChange = 0.01
 *       positionMaxNormalSize = [10, 5]
 *       maxLongExposure = 10
 *       maxShortExposure = 10
 *     }
 *   }
 * }
 */
public class MarketMakerAlgorithmFactory extends AbstractAlgorithmFactory {

    // Instrument
    @Required
    @Alphanumeric
    private long exchange; // Target (quoting) exchange
    @Required
    @Alphanumeric
    private long sourceExchange; // Source exchange

    // Pricer
    @Required
    private double[] buyQuoteSizes; // Array of sizes of buy quotes
    @Required
    private double[] sellQuoteSizes;
    @Required
    private double[] buyMargins; // Array of margins/markdowns for buy quotes
    @Required
    private double[] sellMargins; // Array of margins/markups for sell quotes
    @Required
    private double minSpread; // Minimal distance between top buy and sell quotes
    @Required
    private double minPriceChange; // Minimal distance of quote price from a corresponding order limit price
    @Optional
    private double minSizeChange; // Minimal distance of quote size from a corresponding order size

    // Hedger
    @Required
    private double positionNormalSize;
    @Required
    private double positionMaxSize;
    @Optional
    private double maxOrderSize;
    @Optional
    private long resendTimeMs;
    @Optional
    private double maxOffset;
    @Optional
    private double minHedgePriceChange;
    @Optional
    private double minHedgeSizeChange;

    // Risk Limits
    @Required
    private double maxLongExposure; // The maximum allowable value for the sum of the current position size and the total buy quantity of the bot on the market
    @Required
    private double maxShortExposure; // The maximum allowable value for the difference between the current position size and the total sell quantity of the bot on the market
    @Optional
    private long minBuyQuoteActiveTime; // Minimum time (in milliseconds) to keep a quoting buy order on the market before cancelling or replacing it with a new order
    @Optional
    private long minSellQuoteActiveTime;
    @Optional
    private double maxQuoterPositionSize; // The upper limit (by absolute value) of the QuoterNetQty of the trading bot
    @Optional
    private double maxHedgerPositionSize; // The upper limit of the HedgerNetQty of the trading bot

    // Misc
    @Optional
    private int rateLimit = 1000; // Messages per second

    public MarketMakerAlgorithmFactory() {
        setOrderCacheCapacity(1000);
        setMaxInactiveOrdersCacheSize(1000);
        setInitialActiveOrdersCacheSize(1000);
        setInitialClientsCapacity(16);
    }

    public void setExchange(@Alphanumeric long exchange) {
        this.exchange = exchange;
    }

    public void setSourceExchange(@Alphanumeric long sourceExchange) {
        this.sourceExchange = sourceExchange;
    }

    public void setBuyQuoteSizes(List<Double> buyQuoteSizes) {
        this.buyQuoteSizes = buyQuoteSizes.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public void setSellQuoteSizes(List<Double> sellQuoteSizes) {
        this.sellQuoteSizes = sellQuoteSizes.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public void setBuyMargins(List<Double> buyMargins) {
        this.buyMargins = buyMargins.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public void setSellMargins(List<Double> sellMargins) {
        this.sellMargins = sellMargins.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public void setMinSpread(double minSpread) {
        this.minSpread = minSpread;
    }

    public void setMinPriceChange(double minPriceChange) {
        this.minPriceChange = minPriceChange;
    }

    public void setMinSizeChange(double minSizeChange) {
        this.minSizeChange = minSizeChange;
    }

    public void setPositionNormalSize(double positionNormalSize) {
        this.positionNormalSize = positionNormalSize;
    }

    public void setPositionMaxSize(double positionMaxSize) {
        this.positionMaxSize = positionMaxSize;
    }

    public void setMaxOrderSize(double maxOrderSize) {
        this.maxOrderSize = maxOrderSize;
    }

    public void setResendTimeMs(long resendTimeMs) {
        this.resendTimeMs = resendTimeMs;
    }

    public void setMaxOffset(double maxOffset) {
        this.maxOffset = maxOffset;
    }

    public void setMinHedgePriceChange(double minHedgePriceChange) {
        this.minHedgePriceChange = minHedgePriceChange;
    }

    public void setMinHedgeSizeChange(double minHedgeSizeChange) {
        this.minHedgeSizeChange = minHedgeSizeChange;
    }

    public void setMaxLongExposure(double maxLongExposure) {
        this.maxLongExposure = maxLongExposure;
    }

    public void setMaxShortExposure(double maxShortExposure) {
        this.maxShortExposure = maxShortExposure;
    }

    public void setMinBuyQuoteActiveTime(long minBuyQuoteActiveTime) {
        this.minBuyQuoteActiveTime = minBuyQuoteActiveTime;
    }

    public void setMinSellQuoteActiveTime(long minSellQuoteActiveTime) {
        this.minSellQuoteActiveTime = minSellQuoteActiveTime;
    }

    public void setMaxQuoterPositionSize(long maxQuoterPositionSize) {
        this.maxQuoterPositionSize = maxQuoterPositionSize;
    }

    public void setMaxHedgerPositionSize(long maxHedgerPositionSize) {
        this.maxHedgerPositionSize = maxHedgerPositionSize;
    }

    public void setRateLimit(int rateLimit) {
        this.rateLimit = rateLimit;
    }

    @Override
    public MarketMakerAlgorithm create(AlgorithmContext context) {
        MarketMakerSettings settings = new MarketMakerSettings();
        settings.setExchange(exchange);
        settings.setSourceExchange(sourceExchange);
        settings.setBuyQuoteSizes(buyQuoteSizes);
        settings.setSellQuoteSizes(sellQuoteSizes);
        settings.setBuyMargins(buyMargins);
        settings.setSellMargins(sellMargins);
        settings.setMinSpread(minSpread);
        settings.setMinPriceChange(minPriceChange);
        settings.setMinSizeChange(minSizeChange);
        settings.setPositionNormalSize(positionNormalSize);
        settings.setPositionMaxSize(positionMaxSize);
        settings.setMaxOrderSize(maxOrderSize);
        settings.setResendTimeMs(resendTimeMs);
        settings.setMaxOffset(maxOffset);
        settings.setMinHedgePriceChange(minHedgePriceChange);
        settings.setMinHedgeSizeChange(minHedgeSizeChange);
        settings.setMaxLongExposure(maxLongExposure);
        settings.setMaxShortExposure(maxShortExposure);
        settings.setMinBuyQuoteActiveTime(minBuyQuoteActiveTime);
        settings.setMinSellQuoteActiveTime(minSellQuoteActiveTime);
        settings.setMaxQuoterPositionSize(maxQuoterPositionSize);
        settings.setMaxHedgerPositionSize(maxHedgerPositionSize);
        settings.setRateLimit(rateLimit);

        return new MarketMakerAlgorithm(context, getCacheSettings(), settings);
    }
}
