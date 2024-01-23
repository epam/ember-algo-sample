package deltix.ember.samples.algorithm.orderbook;

import deltix.anvil.util.Factory;
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
import deltix.quoteflow.orderbook.interfaces.DisconnectBehaviour;

/**
 * Sample that demonstrates how to use Deltix QuoteFlow Order Book in algorithms
 */
abstract class AbstractOrderBookSampleAlgorithm extends AbstractAlgorithm<AlgoOrder, AbstractOrderBookSampleAlgorithm.AbstractSampleOrderBook> {

    //TODO//issue#813//private final OrderBookPools sharedOrderBookPools = new OrderBookPools();

    public AbstractOrderBookSampleAlgorithm(AlgorithmContext context, OrdersCacheSettings cacheSettings) {
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
    protected abstract InstrumentDataFactory<AbstractSampleOrderBook> createInstrumentDataFactory();


    public abstract static class AbstractSampleOrderBook extends AbstractInstrumentData {

        protected final FullOrderBook orderBook;

        AbstractSampleOrderBook(CharSequence symbol, InstrumentType instrumentType) {
            super(symbol, instrumentType);
            this.orderBook = createOrderBook();
            this.orderBook.setDisconnectBehaviour(DisconnectBehaviour.CLEAR_EXCHANGE);
        }

        public abstract FullOrderBook createOrderBook();

        @Override
        public String getSymbol() {
            return (String) orderBook.getSymbol();
        }

        /** Feed order book from algorithm subscription */
        @Override
        public abstract void onMarketMessage(InstrumentMessage message);
    }

    /** Here is how you can access current order books for a given symbol */
    private FullOrderBook getOrderBook(CharSequence symbol) {
        AbstractSampleOrderBook orderBook = get(symbol);
        if (orderBook != null)
            return orderBook.orderBook;
        return null;
    }
}
