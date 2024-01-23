package deltix.ember.samples.algorithm.orderbook;

import com.epam.deltix.dfp.Decimal64Utils;
import com.epam.deltix.gflog.api.Log;
import com.epam.deltix.gflog.api.LogFactory;
import deltix.ember.message.smd.InstrumentType;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.md.InstrumentDataFactory;
import deltix.ember.service.oms.cache.OrdersCacheSettings;
import deltix.qsrv.hf.pub.InstrumentMessage;
import deltix.quoteflow.orderbook.FullOrderBook;
import deltix.quoteflow.orderbook.OrderBookWaitingSnapshotMode;
import deltix.quoteflow.orderbook.interfaces.OrderBookLevel;
import deltix.timebase.api.messages.MarketMessageInfo;
import deltix.timebase.api.messages.QuoteSide;

import static deltix.timebase.api.messages.DataModelType.LEVEL_THREE;

/**
 * Sample that demonstrates how to use Deltix QuoteFlow Order Book in algorithms
 */
public class AggregatedOrderBookSampleAlgorithm extends AbstractOrderBookSampleAlgorithm {

    public AggregatedOrderBookSampleAlgorithm(AlgorithmContext context, OrdersCacheSettings cacheSettings) {
        super(context, cacheSettings);
    }

    /** Tell container we use SampleOrderBook instances to process market data for each instrument */
    @Override
    protected InstrumentDataFactory<AbstractSampleOrderBook> createInstrumentDataFactory() {
        return SampleOrderBook::new;
    }


    static final class SampleOrderBook extends AbstractSampleOrderBook {

        private final Log LOGGER;

        SampleOrderBook(CharSequence symbol, InstrumentType instrumentType) {
            super(symbol, instrumentType);
            LOGGER = LogFactory.getLog(SampleOrderBook.class);
        }

        @Override
        public FullOrderBook createOrderBook() {
            return new FullOrderBook(getSymbol(), OrderBookWaitingSnapshotMode.WAITING_FOR_SNAPSHOT, null, LEVEL_THREE);
        }

        /** Feed order book from algorithm subscription and calculate total price for volume */
        @Override
        public void onMarketMessage(InstrumentMessage message) {
            if (message instanceof MarketMessageInfo) {
                orderBook.update((MarketMessageInfo) message);

                // to buy
                long goalVolume = Decimal64Utils.TEN;
                long curMoney = Decimal64Utils.ZERO;
                long curVolume = Decimal64Utils.ZERO;
                for (OrderBookLevel item : orderBook.getAllLevels(QuoteSide.ASK)) {
                    if (Decimal64Utils.isGreaterOrEqual(curMoney, goalVolume))
                        break;

                    curMoney = Decimal64Utils.add(curMoney, Decimal64Utils.multiply(item.getPrice(), Decimal64Utils.min(item.getSize(), Decimal64Utils.subtract(goalVolume, curVolume))));
                    curVolume = Decimal64Utils.add(curVolume, item.getSize());
                }
                curVolume = Decimal64Utils.min(curVolume, goalVolume);

                // apply results somehow, but avoid using logging on real market data
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("You need %s to buy %s")
                            .withDecimal64(curMoney)
                            .withDecimal64(curVolume);
                }

                // to sell
                curMoney = Decimal64Utils.ZERO;
                curVolume = Decimal64Utils.ZERO;
                for (OrderBookLevel item : orderBook.getAllLevels(QuoteSide.BID)) {
                    if (Decimal64Utils.isGreaterOrEqual(curMoney, goalVolume))
                        break;

                    curMoney = Decimal64Utils.add(curMoney, Decimal64Utils.multiply(item.getPrice(), Decimal64Utils.min(item.getSize(), Decimal64Utils.subtract(goalVolume, curVolume))));
                    curVolume = Decimal64Utils.add(curVolume, item.getSize());
                }
                curVolume = Decimal64Utils.min(curVolume, goalVolume);

                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("You need %s to sell %s")
                            .withDecimal64(curMoney)
                            .withDecimal64(curVolume);
                }
            }
        }
    }

}
