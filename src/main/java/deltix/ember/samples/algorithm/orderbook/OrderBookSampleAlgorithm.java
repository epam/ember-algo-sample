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
import deltix.qsrv.hf.pub.InstrumentMessage;
import deltix.quoteflow.orderbook.FullOrderBook;
import deltix.quoteflow.orderbook.OrderBookWaitingSnapshotMode;
import deltix.quoteflow.orderbook.interfaces.DisconnectBehaviour;
import deltix.quoteflow.orderbook.interfaces.ExchangeOrderBook;
import deltix.quoteflow.orderbook.interfaces.OrderBookLevel;
import deltix.quoteflow.orderbook.interfaces.OrderBookList;
import deltix.timebase.api.messages.DataModelType;
import deltix.timebase.api.messages.MarketMessageInfo;
import deltix.timebase.api.messages.QuoteSide;
import deltix.containers.generated.DecimalLongDecimalLongPair;

/**
 * Sample that demonstrates how to use Deltix QuoteFlow Order Book in algorithms
 */
public class OrderBookSampleAlgorithm extends AbstractAlgorithm<AlgoOrder, OrderBookSampleAlgorithm.SampleOrderBook> {

    //TODO//issue#813//private final OrderBookPools sharedOrderBookPools = new OrderBookPools();
    private final DecimalLongDecimalLongPair longlong = new DecimalLongDecimalLongPair();


    public OrderBookSampleAlgorithm(AlgorithmContext context, OrdersCacheSettings cacheSettings) {
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
        return SampleOrderBook::new;
    }


    static final class SampleOrderBook extends AbstractInstrumentData {

        private final FullOrderBook orderBook;

        SampleOrderBook(CharSequence symbol, InstrumentType instrumentType) {
            super(symbol, instrumentType);
            this.orderBook = new FullOrderBook(getSymbol(), OrderBookWaitingSnapshotMode.WAITING_FOR_SNAPSHOT, null, DataModelType.LEVEL_TWO);
            this.orderBook.setDisconnectBehaviour(DisconnectBehaviour.CLEAR_EXCHANGE);
        }

        @Override
        public String getSymbol() {
            return (String) orderBook.getSymbol();
        }


        @Alphanumeric
        private static final long BINANCE_EXCHANGE_ID = AlphanumericCodec.encode("BINANCE");

        @Alphanumeric
        private static final long COINBASE_EXCHANGE_ID = AlphanumericCodec.encode("COINBASE");

        @Decimal
        private static final long PRICE_DELTA = Decimal64Utils.parse("0.001");

        /** Feed order book from algorithm subscription */
        @Override
        public void onMarketMessage(InstrumentMessage message) {
            if (message instanceof MarketMessageInfo) {
                orderBook.update((MarketMessageInfo) message);

                ExchangeOrderBook binanceBook = orderBook.getExchange(BINANCE_EXCHANGE_ID);
                ExchangeOrderBook coinbaseBook = orderBook.getExchange(COINBASE_EXCHANGE_ID);

                OrderBookLevel binanceBestAsk = binanceBook.getBestAskLevel();
                OrderBookLevel coinbaseBestAsk = coinbaseBook.getBestAskLevel();

                @Decimal long priceDiff = Decimal64Utils.subtract(binanceBestAsk.getPrice(), coinbaseBestAsk.getPrice());
                if (Decimal64Utils.isGreaterOrEqual(priceDiff, PRICE_DELTA)) {
                    ///...
                }
            }
        }
    }

    /** Here is how you can access current order books for a given symbol */
    private FullOrderBook getOrderBook (CharSequence symbol) {
        SampleOrderBook orderBook = get(symbol);
        if (orderBook != null)
            return orderBook.orderBook;
        return null;
    }

    private static final @Decimal long HUNDRED = Decimal64Utils.fromLong(100);

    /** Very basic illustration of Full Order Book component API. Refer to QuoteFlow  */
    private void iterateAggregatedBook (FullOrderBook aggregatedBook) {

        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("You need %s to buy %s")
                    .withDecimal64(aggregatedBook.getMoneyForVolume(HUNDRED, QuoteSide.ASK, longlong).getSecond())
                    .withDecimal64(HUNDRED);

            LOGGER.trace("VWAP ask:%s bid:%s")
                    .withDecimal64(calculateVWAP(aggregatedBook.getTopLevels(100, QuoteSide.BID)))
                    .withDecimal64(calculateVWAP(aggregatedBook.getTopLevels(100, QuoteSide.ASK)));
        }
    }

    private static final @Alphanumeric long BINANCE_ID = AlphanumericCodec.encode("BINANCE");

    /** Similar sample for per-exchange book API */
    private void inspectExchangeBook (FullOrderBook aggregatedBook) {
        ExchangeOrderBook singleBook = aggregatedBook.getExchange(BINANCE_ID);

        if (LOGGER.isTraceEnabled() && singleBook != null) {
            LOGGER.trace("You need %s to buy %s on BINANCE")
                    .withDecimal64(singleBook.getMoneyForVolume(HUNDRED, QuoteSide.ASK, longlong).getSecond())
                    .withDecimal64(HUNDRED);

            LOGGER.trace("BINANCE VWAP ask:%s bid:%s")
                    .withDecimal64(calculateVWAP(singleBook.getTopLevels(100, QuoteSide.BID)))
                    .withDecimal64(calculateVWAP(singleBook.getTopLevels(100, QuoteSide.ASK)));
        }
    }

    private static @Decimal long calculateVWAP (OrderBookList<OrderBookLevel> levels) {
        @Decimal long totalValue = Decimal64Utils.ZERO;
        @Decimal long totalSize = Decimal64Utils.ZERO;
        for (int i=0; i < levels.size(); i++) {
            OrderBookLevel level = levels.getObjectAt(i);
            totalValue = Decimal64Utils.add(totalValue, Decimal64Utils.multiply(level.getPrice(), level.getSize()));
            totalSize = Decimal64Utils.add(totalSize, level.getSize());
        }
        if (Decimal64Utils.isZero(totalSize))
            return Decimal64Utils.ZERO;
        return Decimal64Utils.divide(totalValue, totalSize);
    }

}


