package deltix.ember.samples.algorithm.bollinger.v2;

import deltix.ember.message.smd.InstrumentType;
import deltix.ember.service.algorithm.v2.AbstractL1TradingAlgorithm;
import deltix.util.finmath.SMAV;

class BollingerL1InstrumentState extends AbstractL1TradingAlgorithm.L1InstrumentState {

    final SMAV smav;

    /**
     * Actual position (negative if short)
     */
    double positionSize = 0;

    /**
     * Size of pending order (can be negative for sell)
     */
    double pendingSize = 0;

    double positionCost = 0;

    double positionCash = 0;


    BollingerL1InstrumentState(CharSequence symbol, InstrumentType instrumentType, int period) {
        super(symbol, instrumentType);
        smav = new SMAV(period);
    }

    double getProfitRatio(double price) {
        final double basis = positionCost - positionCash;
        return (positionSize * price / basis - 1);
    }

}
