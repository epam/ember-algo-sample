package deltix.ember.samples.algorithm.bollinger.v1;

import deltix.anvil.util.CharSequenceUtil;
import deltix.ember.message.smd.InstrumentType;
import deltix.ember.service.algorithm.md.AbstractInstrumentData;
import deltix.qsrv.hf.pub.InstrumentMessage;
import deltix.util.finmath.SMAV;

/**
 * Keeps Bollinger Band Algorithm position for each instrument
 */
class BollingerBandInstrumentData extends AbstractInstrumentData {

    final BollingerBandInstrumentMessage data = new BollingerBandInstrumentMessage();
    SMAV smav;

    BollingerBandInstrumentData(CharSequence symbol, InstrumentType instrumentType, int period) {
        super(symbol, instrumentType);
        data.setSymbol(CharSequenceUtil.toString(symbol.toString())); // make symbol immutable
        smav = new SMAV(period);
    }

    @Override
    public void onMarketMessage(InstrumentMessage message) {
        // by default we do nothing here (we don't need to remember prices in this algo)
    }


    double getProfitRatio(double price) {
        final double basis = data.positionCost - data.positionCash;
        return (data.positionSize * price / basis - 1);
    }

    boolean addToSMA(double price) {
        smav.register(price);
        return smav.isFull();
    }

}

