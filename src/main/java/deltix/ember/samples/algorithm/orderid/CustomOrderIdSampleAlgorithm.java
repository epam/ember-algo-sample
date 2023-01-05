package deltix.ember.samples.algorithm.orderid;

import deltix.anvil.util.AsciiStringBuilder;
import deltix.anvil.util.Factory;
import deltix.ember.service.algorithm.AbstractAlgorithm;
import deltix.ember.service.algorithm.AlgoOrder;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.ChildOrder;
import deltix.ember.service.algorithm.md.InstrumentDataFactory;
import deltix.ember.service.algorithm.md.SimpleInstrumentPrices;
import deltix.ember.service.oms.cache.OrdersCacheSettings;

/** Sample that demonstrates how to use custom order IDs that algorithm uses for child orders */
public class CustomOrderIdSampleAlgorithm extends AbstractAlgorithm<AlgoOrder, SimpleInstrumentPrices> {

    private final AsciiStringBuilder buffer = new AsciiStringBuilder();


    public CustomOrderIdSampleAlgorithm(AlgorithmContext context, OrdersCacheSettings cacheSettings) {
        super(context, cacheSettings);
    }

    @Override
    protected CharSequence generateOrderId() {
        // convert INT64 to text used by ember
        return buffer.clear().append(GlobalIdGenerator.INSTANCE.next()); // thread safe
    }

    /// region Boilerplate code


    @Override
    protected Factory<AlgoOrder> createParentOrderFactory() {
        return AlgoOrder::new;
    }

    @Override
    protected Factory<ChildOrder<AlgoOrder>> createChildOrderFactory() {
        return ChildOrder::new;
    }

    @Override
    protected InstrumentDataFactory<SimpleInstrumentPrices> createInstrumentDataFactory() {
        return SimpleInstrumentPrices::new;
    }

    /// endregion
}
