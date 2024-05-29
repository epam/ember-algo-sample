package deltix.ember.samples.algorithm.orderbook;

import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.anvil.util.Factory;
import deltix.anvil.util.annotation.Alphanumeric;
import deltix.anvil.util.codec.AlphanumericCodec;
import deltix.ember.message.smd.InstrumentType;
import deltix.ember.service.algorithm.AbstractAlgorithm;
import deltix.ember.service.algorithm.AlgoOrder;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.ChildOrder;
import deltix.ember.service.algorithm.md.AbstractInstrumentData;
import deltix.ember.service.algorithm.md.InstrumentDataFactory;
import deltix.ember.service.oms.cache.OrdersCacheSettings;
import deltix.orderbook.core.api.*;
import deltix.orderbook.core.options.*;
import deltix.qsrv.hf.pub.InstrumentMessage;
import deltix.timebase.api.messages.DataModelType;
import deltix.timebase.api.messages.MarketMessageInfo;
import deltix.timebase.api.messages.QuoteSide;
import rtmath.finanalysis.indicators.SMA;

import javax.annotation.Nonnull;
import java.util.concurrent.TimeUnit;

public class NewOrderBookSampleAlgorithm  extends AbstractAlgorithm<AlgoOrder, NewOrderBookSampleAlgorithm.SampleOrderBook> {

    private static final @Alphanumeric long BINANCE_EXCHANGE_ID = AlphanumericCodec.encode("BINANCE");
    private static final @Alphanumeric long COINBASE_EXCHANGE_ID = AlphanumericCodec.encode("COINBASE");

    private static final @Decimal long FIVE_CONTRACTS = Decimal64Utils.fromInt(5);
    private static final @Decimal long HUNDRED = Decimal64Utils.fromLong(100);
    private static final @Decimal long PRICE_DELTA = Decimal64Utils.parse("0.001");
    private static final long SMA_TIME_PERIOD = TimeUnit.MINUTES.toMillis(5);

    private final long [] moneyVolume = new long[2];

    private static final OrderBookOptions COMMON_ORDER_BOOK_OPTIONS = new OrderBookOptionsBuilder()
            .quoteLevels(DataModelType.LEVEL_TWO)
            //.initialMaxDepth(marketDepth)
            //.initialExchangesPoolSize(exchangeIds.length)
            .updateMode(UpdateMode.WAITING_FOR_SNAPSHOT)
            .orderBookType(OrderBookType.AGGREGATED)
            .disconnectMode(DisconnectMode.CLEAR_EXCHANGE)
            .build();

    public NewOrderBookSampleAlgorithm(AlgorithmContext context, OrdersCacheSettings cacheSettings) {
        super(context, cacheSettings);
    }

    @Override
    protected Factory<AlgoOrder> createParentOrderFactory() {
        return AlgoOrder::new;
    }

    @Override
    protected Factory<ChildOrder<AlgoOrder>> createChildOrderFactory() {
        return ChildOrder::new;
    }

    /** Tell container we use SampleOrderBook instances to process market data for each instrument */
    @Override
    protected InstrumentDataFactory<SampleOrderBook> createInstrumentDataFactory() {
        return NewOrderBookSampleAlgorithm.SampleOrderBook::new;
    }


    static final class SampleOrderBook extends AbstractInstrumentData {

        private final OrderBook<OrderBookQuote> orderBook;

        private final SMA sma = new SMA(SMA_TIME_PERIOD);

        SampleOrderBook(CharSequence symbol, InstrumentType instrumentType) {
            super(symbol, instrumentType);

            final OrderBookOptions orderBookOptions = new OrderBookOptionsBuilder()
                    .parent(COMMON_ORDER_BOOK_OPTIONS)
                    .symbol(getSymbol())
                    .build();

            this.orderBook = OrderBookFactory.create(orderBookOptions);

        }

        @Override
        public String getSymbol() {
            return orderBook.getSymbol().get();
        }


        /** Feed order book from algorithm subscription */
        @Override
        public void onMarketMessage(InstrumentMessage message) {
            if (message instanceof MarketMessageInfo) {
                orderBook.update((MarketMessageInfo) message);

                Option<? extends Exchange<OrderBookQuote>> binanceBook = orderBook.getExchanges().getById(BINANCE_EXCHANGE_ID);
                Option<? extends Exchange<OrderBookQuote>> coinbaseBook = orderBook.getExchanges().getById(COINBASE_EXCHANGE_ID);

                if (binanceBook.hasValue() && coinbaseBook.hasValue()) {
                    OrderBookQuote binanceBestAsk = binanceBook.get().getMarketSide(QuoteSide.ASK).getBestQuote();
                    OrderBookQuote coinbaseBestAsk = coinbaseBook.get().getMarketSide(QuoteSide.ASK).getBestQuote();
                    if (binanceBestAsk != null && coinbaseBestAsk != null) {
                        @Decimal long priceDiff = Decimal64Utils.subtract(binanceBestAsk.getPrice(), coinbaseBestAsk.getPrice());
                        if (Decimal64Utils.isGreaterOrEqual(priceDiff, PRICE_DELTA)) {
                            ///...
                        }
                        sma.add(priceDiff, message.getTimeStampMs());
                    }
                }
            }
        }
    }

    /** Here is how you can access current order books for a given symbol */
    private OrderBook<OrderBookQuote> getOrderBook (CharSequence symbol) {
        SampleOrderBook orderBook = get(symbol);
        if (orderBook != null)
            return orderBook.orderBook;
        return null;
    }


    /** Very basic illustration of Full Order Book component API. Refer to QuoteFlow  */
    private void iterateAggregatedBook (OrderBook<OrderBookQuote> aggregatedBook) {

        if (LOGGER.isTraceEnabled()) {
            getMoneyForVolume(aggregatedBook.getMarketSide(QuoteSide.ASK), HUNDRED, moneyVolume);

            LOGGER.trace("You need %s to buy %s")
                    .withDecimal64(moneyVolume[0])
                    .withDecimal64(HUNDRED);

            LOGGER.trace("VWAP ask:%s bid:%s")
                    .withDecimal64(calculateVWAP(aggregatedBook.getMarketSide(QuoteSide.BID), FIVE_CONTRACTS))
                    .withDecimal64(calculateVWAP(aggregatedBook.getMarketSide(QuoteSide.ASK), FIVE_CONTRACTS));
        }
    }


    /** Similar sample for per-exchange book API */
    private void inspectExchangeBook (OrderBook<OrderBookQuote> aggregatedBook) {
        Option<? extends Exchange<OrderBookQuote>> singleBook = aggregatedBook.getExchanges().getById(BINANCE_EXCHANGE_ID);

        if (LOGGER.isTraceEnabled() && singleBook.hasValue()) {
            getMoneyForVolume(singleBook.get().getMarketSide(QuoteSide.ASK), HUNDRED, moneyVolume);
            LOGGER.trace("You need %s to buy %s on BINANCE")
                    .withDecimal64(moneyVolume[0])
                    .withDecimal64(HUNDRED);

            LOGGER.trace("BINANCE VWAP ask:%s bid:%s")
                    .withDecimal64(calculateVWAP(singleBook.get().getMarketSide(QuoteSide.BID), FIVE_CONTRACTS))
                    .withDecimal64(calculateVWAP(singleBook.get().getMarketSide(QuoteSide.ASK), FIVE_CONTRACTS));
        }
    }

    private static @Decimal long calculateVWAP (MarketSide<OrderBookQuote> levels, @Decimal long targetVolume) {
        @Decimal long totalValue = Decimal64Utils.ZERO;
        @Decimal long totalSize = Decimal64Utils.ZERO;
        for (int i=0; i < levels.depth(); i++) {
            OrderBookQuote level = levels.getQuote(i);
            totalValue = Decimal64Utils.add(totalValue, Decimal64Utils.multiply(level.getPrice(), level.getSize()));
            totalSize = Decimal64Utils.add(totalSize, level.getSize());
            if (Decimal64Utils.isGreater(totalSize, targetVolume))
                break;
        }
        if (Decimal64Utils.isZero(totalSize))
            return Decimal64Utils.ZERO;
        return Decimal64Utils.divide(totalValue, totalSize);
    }


    /**
     * Return money, which we must have, if we want to buy/sell given volume in order book.
     *
     * @param volume Volume to acquire.
     * @param result Resulting pair (we will return this object) of this method (first element of pair - money, second element of pair - volume).
     * @return  Resulting pair (param result) of this method (first element of pair - money, second element of pair - volume).
     */
    @Decimal
    @Nonnull
    static long [] getMoneyForVolume (MarketSide<OrderBookQuote> marketSide, @Decimal long volume, @Nonnull long [] result) {
        @Decimal long money = Decimal64Utils.ZERO;
        @Decimal long _volume = Decimal64Utils.ZERO;
        final int depth = marketSide.depth();
        for (int i=0; i < depth; i++) {
            OrderBookQuote quote = marketSide.getQuote(i);
            if (Decimal64Utils.isGreaterOrEqual(_volume, volume))
                break;
            money = Decimal64Utils.add(money, Decimal64Utils.multiply(quote.getPrice(), Decimal64Utils.min(quote.getSize(), Decimal64Utils.subtract(volume,  _volume))));
            _volume = Decimal64Utils.add(_volume, quote.getSize());
        }
        result[0] = money;
        result[1] = Decimal64Utils.min(_volume, volume);
        return result;
    }
}


