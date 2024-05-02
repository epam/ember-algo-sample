package deltix.ember.samples.algorithm.marketmaker;

import deltix.anvil.util.annotation.Alphanumeric;
import deltix.anvil.util.annotation.Required;

public class MarketMakerSettings {

    // Instrument
    @Required
    @Alphanumeric
    private long exchange;
    @Required
    @Alphanumeric
    private long sourceExchange;

    // Pricer
    private double[] buyQuoteSizes;
    private double[] sellQuoteSizes;
    private double[] buyMargins;
    private double[] sellMargins;
    private double minSpread;
    private double minPriceChange;
    private double minSizeChange;

    // Hedger
    private double positionNormalSize;
    private double positionMaxSize;
    private double maxOrderSize;
    private long resendTimeMs;
    private double maxOffset;
    private double minHedgePriceChange;
    private double minHedgeSizeChange;

    // Risk Limits
    private double maxLongExposure;
    private double maxShortExposure;
    private long minBuyQuoteActiveTime;
    private long minSellQuoteActiveTime;
    private double maxQuoterPositionSize;
    private double maxHedgerPositionSize;

    // Misc
    private int rateLimit;

    public @Alphanumeric long getExchange() {
        return exchange;
    }

    public void setExchange(@Alphanumeric long exchange) {
        this.exchange = exchange;
    }

    public @Alphanumeric long getSourceExchange() {
        return sourceExchange;
    }

    public void setSourceExchange(@Alphanumeric long sourceExchange) {
        this.sourceExchange = sourceExchange;
    }

    public double[] getBuyQuoteSizes() {
        return buyQuoteSizes;
    }

    public void setBuyQuoteSizes(double[] buyQuoteSizes) {
        this.buyQuoteSizes = buyQuoteSizes;
    }

    public double[] getSellQuoteSizes() {
        return sellQuoteSizes;
    }

    public void setSellQuoteSizes(double[] sellQuoteSizes) {
        this.sellQuoteSizes = sellQuoteSizes;
    }

    public double[] getBuyMargins() {
        return buyMargins;
    }

    public void setBuyMargins(double[] buyMargins) {
        this.buyMargins = buyMargins;
    }

    public double[] getSellMargins() {
        return sellMargins;
    }

    public void setSellMargins(double[] sellMargins) {
        this.sellMargins = sellMargins;
    }

    public double getMinSpread() {
        return minSpread;
    }

    public void setMinSpread(double minSpread) {
        this.minSpread = minSpread;
    }

    public double getMinPriceChange() {
        return minPriceChange;
    }

    public void setMinPriceChange(double minPriceChange) {
        this.minPriceChange = minPriceChange;
    }

    public double getMinSizeChange() {
        return minSizeChange;
    }

    public void setMinSizeChange(double minSizeChange) {
        this.minSizeChange = minSizeChange;
    }

    public double getPositionNormalSize() {
        return positionNormalSize;
    }

    public double getPositionMaxSize() {
        return positionMaxSize;
    }

    public void setPositionNormalSize(double positionNormalSize) {
        this.positionNormalSize = positionNormalSize;
    }

    public void setPositionMaxSize(double positionMaxSize) {
        this.positionMaxSize = positionMaxSize;
    }

    public double getMaxOrderSize() {
        return maxOrderSize;
    }

    public void setMaxOrderSize(double maxOrderSize) {
        this.maxOrderSize = maxOrderSize;
    }

    public long getResendTimeMs() {
        return resendTimeMs;
    }

    public void setResendTimeMs(long resendTimeMs) {
        this.resendTimeMs = resendTimeMs;
    }

    public double getMaxOffset() {
        return maxOffset;
    }

    public void setMaxOffset(double maxOffset) {
        this.maxOffset = maxOffset;
    }

    public double getMinHedgePriceChange() {
        return minHedgePriceChange;
    }

    public void setMinHedgePriceChange(double minHedgePriceChange) {
        this.minHedgePriceChange = minHedgePriceChange;
    }

    public double getMinHedgeSizeChange() {
        return minHedgeSizeChange;
    }

    public void setMinHedgeSizeChange(double minHedgeSizeChange) {
        this.minHedgeSizeChange = minHedgeSizeChange;
    }

    public double getMaxLongExposure() {
        return maxLongExposure;
    }

    public void setMaxLongExposure(double maxLongExposure) {
        this.maxLongExposure = maxLongExposure;
    }

    public double getMaxShortExposure() {
        return maxShortExposure;
    }

    public void setMaxShortExposure(double maxShortExposure) {
        this.maxShortExposure = maxShortExposure;
    }

    public long getMinBuyQuoteActiveTime() {
        return minBuyQuoteActiveTime;
    }

    public void setMinBuyQuoteActiveTime(long minBuyQuoteActiveTime) {
        this.minBuyQuoteActiveTime = minBuyQuoteActiveTime;
    }

    public long getMinSellQuoteActiveTime() {
        return minSellQuoteActiveTime;
    }

    public void setMinSellQuoteActiveTime(long minSellQuoteActiveTime) {
        this.minSellQuoteActiveTime = minSellQuoteActiveTime;
    }

    public double getMaxQuoterPositionSize() {
        return maxQuoterPositionSize;
    }

    public void setMaxQuoterPositionSize(double maxQuoterPositionSize) {
        this.maxQuoterPositionSize = maxQuoterPositionSize;
    }

    public double getMaxHedgerPositionSize() {
        return maxHedgerPositionSize;
    }

    public void setMaxHedgerPositionSize(double maxHedgerPositionSize) {
        this.maxHedgerPositionSize = maxHedgerPositionSize;
    }

    public int getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(int rateLimit) {
        this.rateLimit = rateLimit;
    }
}