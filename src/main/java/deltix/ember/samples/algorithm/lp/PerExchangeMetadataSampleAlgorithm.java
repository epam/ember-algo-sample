package deltix.ember.service.algorithm.samples.lp;

import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.anvil.util.CharSequenceUtil;
import deltix.anvil.util.Factory;
import deltix.anvil.util.annotation.Alphanumeric;
import deltix.ember.message.smd.InstrumentType;
import deltix.ember.message.smd.InstrumentUpdate;
import deltix.ember.service.algorithm.*;
import deltix.ember.service.algorithm.md.*;
import deltix.ember.service.oms.cache.OrdersCacheSettings;
import javax.annotation.Nullable;


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/// This example shows how an algorithm can track an imaginary per-exchange security metadata property myCustomField  //
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


@SuppressWarnings("unused") // this is just a sample
public class PerExchangeMetadataSampleAlgorithm extends AbstractAlgorithm<AlgoOrder, InstrumentDataWithPerExchangeState> {

    PerExchangeMetadataSampleAlgorithm(AlgorithmContext context, OrdersCacheSettings cacheSettings) {
        super(context, cacheSettings);
    }

    /**
     * This method shows how to access some custom attribute defined for exchange specific security metadata record (like BTC/USD.COINBASE)
     * @param symbol Instrument symbol (e.g. BTC/USD)
     * @param exchangeId exchange (as alphanumeric-encoded value), for example COINBASE
     * @return value of "customField" attribute (defined for BTC/USD.COINBASE) or NULL
     */
    @Decimal
    public long getMyCustomField (CharSequence symbol, @Alphanumeric long exchangeId) {
        InstrumentDataWithPerExchangeState instrument = get(symbol);
        return (instrument != null) ? instrument.getMyCustomField(exchangeId) : Decimal64Utils.NULL;
    }

    /// region Factories

    protected MarketDataProcessor<InstrumentDataWithPerExchangeState> createMarketDataProcessor(@Nullable MarketSubscription subscription) {
        return new PerExchangeMarketDataProcessor<>(subscription, 128, createInstrumentDataFactory(), context.getErrorThrottlerManager());
    }

    @Override
    protected InstrumentDataFactory<InstrumentDataWithPerExchangeState> createInstrumentDataFactory() {
        return InstrumentDataWithPerExchangeState::new;
    }

    @Override
    protected Factory<AlgoOrder> createParentOrderFactory() {
        return AlgoOrder::new;
    }

    @Override
    protected Factory<ChildOrder<AlgoOrder>> createChildOrderFactory() {
        return ChildOrder::new;
    }

    /// endregion

}


/** Defines per-instrument data structure that we want to keep. Will also keep per-exchange MyPerExchangeInstrumentMetadata  */
class InstrumentDataWithPerExchangeState extends AbstractInstrumentDataEx<MyPerExchangeInstrumentMetadata> {
    public InstrumentDataWithPerExchangeState(final CharSequence symbol, final InstrumentType instrumentType) {
        super (symbol, instrumentType);
    }

    @Override
    protected MyPerExchangeInstrumentMetadata createPerExchangeInstrument(@Alphanumeric long exchangeId) {
        return new MyPerExchangeInstrumentMetadata(exchangeId);
    }

    public long getMyCustomField(@Alphanumeric long exchangeId) {
        MyPerExchangeInstrumentMetadata lpInfo = getPerExchangeInstrument(exchangeId);

        @Decimal long result = Decimal64Utils.NULL;
        if (lpInfo != null)
            result = lpInfo.getMyCustomField();
        return result;
    }
}


/** This structure will keep per-exchange attributes of each instrument */
class MyPerExchangeInstrumentMetadata extends PerExchangeAlgoInstrumentData {

    @Decimal
    private long myCustomField; // this is just an example of custom attribute that comes from per-exchange security metadata

    public MyPerExchangeInstrumentMetadata(@Alphanumeric long exchangeId) {
        super(exchangeId);
    }

    @Decimal
    public long getMyCustomField() {
        return myCustomField;
    }

    public void setMyCustomField(@Decimal long myCustomField) {
        this.myCustomField = myCustomField;
    }

    /**
     * Will be called on each instrument update (in local "securities" TimeBase stream)
     *
     * @param instrument instrument that is loaded or updated in Timebase "securities" stream
     * @param key custom attribute key
     * @param value custom attribute value
     */
    @Override
    protected void updateCustomAttribute(InstrumentUpdate instrument, CharSequence key, CharSequence value) {
        if (CharSequenceUtil.equals("customField", key))
            setMyCustomField(Decimal64Utils.tryParse(value, Decimal64Utils.NULL));
        else
            super.updateCustomAttribute(instrument, key, value);
    }

}

