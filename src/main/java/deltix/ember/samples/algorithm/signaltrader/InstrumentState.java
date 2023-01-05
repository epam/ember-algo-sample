package deltix.ember.samples.algorithm.signaltrader;

import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.ember.message.smd.InstrumentType;
import deltix.ember.message.trade.Side;
import deltix.ember.service.algorithm.v2.AbstractL1TradingAlgorithm;
import deltix.gflog.AppendableEntry;
import deltix.gflog.Loggable;
import deltix.util.finmath.SMAV;

/** Instrument state such as  position size, cost and P&L */
class InstrumentState extends AbstractL1TradingAlgorithm.L1InstrumentState implements Loggable {

    /** Simple Moving Average (NB: Uses double data type) */
    final SMAV smav;

    /** Actual position (negative if short) */
    private @Decimal long positionSize = Decimal64Utils.ZERO;
    private @Decimal long avgCost = Decimal64Utils.ZERO;
    private @Decimal long realizedPnL = Decimal64Utils.ZERO;

    InstrumentState(CharSequence symbol, InstrumentType instrumentType, SignalTraderSettings settings) {
        super(symbol, instrumentType);
        smav = new SMAV(settings.getNumPeriods());
    }

    @Decimal
    public long getPositionSize() {
        return positionSize;
    }

    @Decimal
    public long getAvgCost() {
        return avgCost;
    }

    @Decimal
    public long getRealizedPnL() {
        return realizedPnL;
    }

    /** Calculate actual position, cost and P&L */
    void updateInstrument(Side side, @Decimal long tradePrice, @Decimal long tradeQuantity) {
        if (side != Side.BUY)
            tradeQuantity = Decimal64Utils.negate(tradeQuantity);

        if (Decimal64Utils.isZero(positionSize)) { // new position
            avgCost = tradePrice;
            positionSize = tradeQuantity;
        } else if (Decimal64Utils.isPositive(positionSize) == Decimal64Utils.isPositive(tradeQuantity)) { // increasing position (even if short one)
            @Decimal long cost = Decimal64Utils.add(Decimal64Utils.multiply(positionSize, avgCost),
                    Decimal64Utils.multiply(tradeQuantity, tradeQuantity));
            avgCost = Decimal64Utils.divide(cost, Decimal64Utils.add(positionSize, tradeQuantity));
            positionSize = Decimal64Utils.add(positionSize, tradeQuantity);
        } else if (Decimal64Utils.isNegative(Decimal64Utils.subtract(Decimal64Utils.abs(positionSize), Decimal64Utils.abs(tradeQuantity)))) { // reverse position
            // closing
            @Decimal long realizedPnL = Decimal64Utils.multiply(positionSize, Decimal64Utils.subtract(tradeQuantity, avgCost));
            this.realizedPnL = Decimal64Utils.add(this.realizedPnL, realizedPnL);

            // opening reverse
            positionSize = Decimal64Utils.add(tradeQuantity, positionSize);
            avgCost = tradeQuantity;
        } else {  // decreasing position (may be closing it)
            @Decimal long realizedPnL = Decimal64Utils.multiply(tradeQuantity, Decimal64Utils.subtract(avgCost, tradeQuantity));
            this.realizedPnL = Decimal64Utils.add(this.realizedPnL, realizedPnL);
            positionSize = Decimal64Utils.add(positionSize, tradeQuantity);
            if (Decimal64Utils.isZero(positionSize))
                avgCost = Decimal64Utils.ZERO;
        }
    }

    @Override
    public void appendTo(AppendableEntry entry) {
        entry.append("symbol: ").append(getSymbol());
        entry.append(", size: ").appendDecimal64(positionSize);
        entry.append(", avgCost: ").appendDecimal64(avgCost);
        entry.append(", realizedPnL: ").appendDecimal64(realizedPnL);
    }
}
