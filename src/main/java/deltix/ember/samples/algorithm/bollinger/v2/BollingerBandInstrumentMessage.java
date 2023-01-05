package deltix.ember.samples.algorithm.bollinger.v2;


import com.epam.deltix.dfp.Decimal64Utils;
import deltix.qsrv.hf.pub.InstrumentMessage;
import deltix.timebase.api.SchemaElement;

/**
 * "Timebase message compatible" state of position
 */
@SuppressWarnings("unused")
public class BollingerBandInstrumentMessage extends InstrumentMessage {

    /** Actual position (negative if short) */
    long positionSize = 0;

    /** Size of pending order (can be negative for sell) */
    long pendingSize = 0;

    double positionCost = Decimal64Utils.ZERO;

    double positionCash = Decimal64Utils.ZERO;

    /** Make TimeBase Introspector happy */
    BollingerBandInstrumentMessage() {}

    @SchemaElement(title="Position Size")
    public long getPositionSize() {
        return positionSize;
    }

    public void setPositionSize(long positionSize) {
        this.positionSize = positionSize;
    }

    @SchemaElement(title="Position Cost")
    public double getPositionCost() {
        return positionCost;
    }

    public void setPositionCost(double positionCost) {
        this.positionCost = positionCost;
    }

    @SchemaElement(title="Position Cash")
    public double getPositionCash() {
        return positionCash;
    }

    public void setPositionCash(double positionCash) {
        this.positionCash = positionCash;
    }
}
