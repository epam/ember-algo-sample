package deltix.ember.samples.algorithm.marketmaker;

import com.epam.deltix.dfp.Decimal64Utils;
import deltix.anvil.util.annotation.Alphanumeric;
import deltix.anvil.util.annotation.Required;
import deltix.containers.AlphanumericUtils;
import deltix.ember.service.algorithm.AbstractAlgorithmFactory;
import deltix.ember.service.algorithm.AlgorithmContext;

/**
 * Arbitrage Algorithm Factory.
 * Here is an example of algorithm config in ember.conf file:
 * algorithms {
 *   MM: ${template.algorithm.default} {
 *     factory = "deltix.ember.samples.algorithm.marketmaker.MarketMakerFactory"
 *     subscription {
 *       streams = [ "OKEX", "BINANCE"]
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
 *       minSizeChange = 0.01
 *       positionMaxNormalSize = [10, 5]
 *       maxOrderSize = 1
 *       resendTimeMs = 1000
 *       maxOffset = 0.01
 *       minHedgePriceChange = 0.01
 *       minHedgeSizeChange = 0.01
 *       maxLongExposure = 10
 *       maxShortExposure = 10
 *       minBuyQuoteActiveTime = 1000
 *       minSellQuoteActiveTime = 1000
 *       maxQuoterPositionSize = 10
 *       maxHedgerPositionSize = 10
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
    private double[] buyQuoteSizes; // Array of sizes of buy quotes
    private double[] sellQuoteSizes; // Array of sizes of sell quotes
    private double[] buyMargins; // Array of margins/markdowns for buy quotes
    private double[] sellMargins; // Array of margins/markups for sell quotes
    private double minSpread; // Minimal distance between top buy and sell quotes
    // private String takeOut; // Taking out rule for third-party orders on the market TODO: implement
    private double minPriceChange; // Minimal distance of quote price from a corresponding order limit price
    private double minSizeChange; // Minimal distance of quote size from a corresponding order size

    // Hedger
    private double positionNormalSize;
    private double positionMaxSize; // Max and normal position size of the bot
    // private String hedgeInstrument; // Hedge instrument TODO: currently use quoted instrument
    // private String[] venuesList; // List of exchanges to execute hedge orders TODO: currently use single exchange (source)
    private double maxOrderSize; // Max order size
    private long resendTimeMs; // Resend time in milliseconds
    private double maxOffset; // Max offset
    private double minHedgePriceChange; // Min price change
    private double minHedgeSizeChange; // Min size change

    // Risk Limits
    private double maxLongExposure; // Maximum long exposure. It's the maximum allowable value for the sum of the current position size and the total buy quantity of the bot on the market.
    private double maxShortExposure; // Maximum short exposure. It's the maximum allowable value for the difference between the current position size and the total sell quantity of the bot on the market.
    private long minBuyQuoteActiveTime; // Minimum time (in milliseconds) to keep a quoting buy order on the market before cancelling or replacing it with a new order. Two values can be specified - Aggressive and Defensive.
    private long minSellQuoteActiveTime; // Minimum time (in milliseconds) to keep a quoting sell order on the market before cancelling or replacing it with a new order. Two values can be specified - Aggressive and Defensive.
    private double maxQuoterPositionSize; // The upper limit (by absolute value) of the QuoterNetQty of the trading bot.
    private double maxHedgerPositionSize; // The upper limit (by absolute value) of the HedgerNetQty of the trading bot.

    // Misc
    private int rateLimit; // per second

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

    public void setBuyQuoteSizes(double[] buyQuoteSizes) {
        this.buyQuoteSizes = buyQuoteSizes;
    }

    public void setSellQuoteSizes(double[] sellQuoteSizes) {
        this.sellQuoteSizes = sellQuoteSizes;
    }

    public void setBuyMargins(double[] buyMargins) {
        this.buyMargins = buyMargins;
    }

    public void setSellMargins(double[] sellMargins) {
        this.sellMargins = sellMargins;
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
        if (buyQuoteSizes == null) {
            throw new IllegalArgumentException("Buy Quote Sizes is required field");
        }
        settings.setBuyQuoteSizes(buyQuoteSizes);
        if (sellQuoteSizes == null) {
            throw new IllegalArgumentException("Sell Quote Sizes is required field");
        }
        settings.setSellQuoteSizes(sellQuoteSizes);
        if (buyMargins == null) {
            throw new IllegalArgumentException("Buy Margins is required field");
        }
        settings.setBuyMargins(buyMargins);
        if (sellMargins == null) {
            throw new IllegalArgumentException("Sell Margins is required field");
        }
        settings.setSellMargins(sellMargins);
        if (minSpread  <= 0) {
            throw new IllegalArgumentException("Min Spread is required field");
        }
        settings.setMinSpread(minSpread);
        if (minPriceChange <= 0) {
            throw new IllegalArgumentException("Min Price Change is required field");
        }
        settings.setMinPriceChange(minPriceChange);
//        if (minSizeChange <= 0) {
//            throw new IllegalArgumentException("Min Size Change is required field");
//        }
        settings.setMinSizeChange(minSizeChange);
        if (positionNormalSize < 0) { // TODO: swap with more robust checks
            throw new IllegalArgumentException("Position Normal Size is required field");
        }
        settings.setPositionNormalSize(positionNormalSize);
        if (positionMaxSize <= 0) {
            throw new IllegalArgumentException("Position Max Size is required field");
        }
        settings.setPositionMaxSize(positionMaxSize);
//        if (maxOrderSize <= 0) {
//            throw new IllegalArgumentException("Max Order Size is required field");
//        }
        settings.setMaxOrderSize(maxOrderSize);
//        if (resendTimeMs <= 0) {
//            throw new IllegalArgumentException("Resend Time Ms is required field");
//        }
        settings.setResendTimeMs(resendTimeMs);
//        if (maxOffset <= 0) {
//            throw new IllegalArgumentException("Max Offset is required field");
//        }
        settings.setMaxOffset(maxOffset);
//        if (minHedgePriceChange <= 0) {
//            throw new IllegalArgumentException("Min Hedge Price Change is required field");
//        }
        settings.setMinHedgePriceChange(minHedgePriceChange);
//        if (minHedgeSizeChange <= 0) {
//            throw new IllegalArgumentException("Min Hedge Size Change is required field");
//        }
        settings.setMinHedgeSizeChange(minHedgeSizeChange);
        if (maxLongExposure <= 0) {
            throw new IllegalArgumentException("Max Long Exposure is required field");
        }
        settings.setMaxLongExposure(maxLongExposure);
        if (maxShortExposure <= 0) {
            throw new IllegalArgumentException("Max Short Exposure is required field");
        }
        settings.setMaxShortExposure(maxShortExposure);
        settings.setMinBuyQuoteActiveTime(minBuyQuoteActiveTime);
        settings.setMinSellQuoteActiveTime(minSellQuoteActiveTime);
//        if (maxQuoterPositionSize <= 0) {
//            throw new IllegalArgumentException("Max Quoter Position Size is required field");
//        }
        settings.setMaxQuoterPositionSize(maxQuoterPositionSize);
//        if (maxHedgerPositionSize <= 0) {
//            throw new IllegalArgumentException("Max Hedger Position Size is required field");
//        }
        settings.setMaxHedgerPositionSize(maxHedgerPositionSize);
        settings.setRateLimit(rateLimit);

        return new MarketMakerAlgorithm(context, getCacheSettings(), settings);
    }
}
