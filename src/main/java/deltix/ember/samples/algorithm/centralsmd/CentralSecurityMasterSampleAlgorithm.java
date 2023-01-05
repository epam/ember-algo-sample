package deltix.ember.samples.algorithm.centralsmd;

import deltix.anvil.util.Factory;
import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.ember.message.smd.InstrumentType;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.md.AbstractInstrumentData;
import deltix.ember.service.algorithm.md.InstrumentDataFactory;
import deltix.ember.service.algorithm.v2.AbstractTradingAlgorithm;
import deltix.ember.service.algorithm.v2.order.OutboundOrder;
import deltix.ember.service.oms.TradingUtils;
import deltix.ember.service.oms.cache.OrdersCacheSettings;
import deltix.qsrv.hf.pub.InstrumentMessage;

import deltix.securitymaster.InstrumentMetadataProvider;
import deltix.securitymaster.messages.CryptoCurrency;
import deltix.timebase.api.messages.securities.GenericInstrument;

import java.util.Collection;
import java.util.List;


/**
 * This class simply illustrates how to use Centralized Security Master.
 * In addition to local security metadata (usually stored in special TimeBase stream called 'securities', Deltix also provides
 * central service. This REST service has Java API available to Ember Algorithms.
 *
 * One important assumption: centralized service uses CCX/CCY symbology for FX and Crypto currencies. For example "EUR/USD".
 *
 * This sample requires the following Deltix libraries in dependency:
 * <pre>
 *   implementation 'deltix:sm-messages:1.3.1'
 *   implementation 'deltix:deltix-sm-api:1.3.1'
 * </pre>
 *
 */
public class CentralSecurityMasterSampleAlgorithm extends AbstractTradingAlgorithm<InstrumentInfo, OutboundOrder> {


    public CentralSecurityMasterSampleAlgorithm(AlgorithmContext context, OrdersCacheSettings cacheSettings) {
        super(context, cacheSettings);
    }

    @Override
    protected Factory<OutboundOrder> createOrderFactory() {
        return OutboundOrder::new;
    }

    @Override
    protected InstrumentDataFactory<InstrumentInfo> createInstrumentDataFactory() {
        return InstrumentInfo::new;
    }

    @Override
    public void open() {
        super.open();

        if (context.getMarketSubscription() != null) {
            // Here we assume that algorithm uses a list of subscription symbols
            List<String> symbols = context.getMarketSubscription().getSymbols();
            InstrumentMetadataProvider metadataProvider = context.getRemoteSecurityMetadataProvider();
            Collection<GenericInstrument> instruments = metadataProvider.getInstruments("COINBASE", symbols.toArray(new String[0]));
            if (instruments != null) {
                for (GenericInstrument instrument : instruments) {
                    getOrCreate(instrument.getSymbol(), TradingUtils.convert(instrument.getInstrumentType(), false)).initializeFrom (instrument);
                }
            }
        }
    }
}

/** Here we cache security metadata information algorithm may later need */
class InstrumentInfo extends AbstractInstrumentData {


    @Decimal
    private long minOrderSize = Decimal64Utils.ZERO;

    @Decimal
    private long orderSizePrecision = Decimal64Utils.NULL;

    public InstrumentInfo(CharSequence symbol, InstrumentType instrumentType) {
        super(symbol, instrumentType);
    }

    @Override
    public void onMarketMessage(InstrumentMessage instrumentMessage) {

    }

    public void initializeFrom(GenericInstrument instrument) {
        if (instrument instanceof CryptoCurrency) {
            CryptoCurrency cryptoCurrency = (CryptoCurrency) instrument;
            minOrderSize = cryptoCurrency.getMinOrderSize();
            orderSizePrecision = cryptoCurrency.getOrderSizePrecision();
        }
    }
}