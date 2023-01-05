package deltix.ember.samples.algorithm.signaltrader;

import deltix.anvil.util.annotation.Optional;
import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;

public class SignalTraderSettings {
    /** Sample only: used to compute Bollinger band */
    @Optional
    private double numStdDevs = 2;

    /** Sample only: used to compute Bollinger band */
    @Optional
    private int numPeriods = 300;

    /** Sample only: used to compute size of ENTER order */
    @Optional
    @Decimal
    private long enterOrderSizeCoefficient = Decimal64Utils.fromLong(10);

    /** Sample only: used to compute price of EXIT order */
    @Optional
    @Decimal
    private long exitOrderPriceCoefficient = Decimal64Utils.fromDouble(0.05);

    /** Sample only: used to compute price of EXIT order */
    @Optional
    @Decimal
    private long stopOrderPriceCoefficient = Decimal64Utils.fromDouble(0.05);

    public double getNumStdDevs() {
        return numStdDevs;
    }

    public void setNumStdDevs(double numStdDevs) {
        this.numStdDevs = numStdDevs;
    }


    public int getNumPeriods() {
        return numPeriods;
    }

    public void setNumPeriods(int numPeriods) {
        this.numPeriods = numPeriods;
    }


    @Decimal
    public long getEnterOrderSizeCoefficient() {
        return enterOrderSizeCoefficient;
    }

    public void setEnterOrderSizeCoefficient(@Decimal long enterOrderSizeCoefficient) {
        this.enterOrderSizeCoefficient = enterOrderSizeCoefficient;
    }

    @Decimal
    public long getExitOrderPriceCoefficient() {
        return exitOrderPriceCoefficient;
    }

    public void setExitOrderPriceCoefficient(@Decimal long exitOrderPriceCoefficient) {
        this.exitOrderPriceCoefficient = exitOrderPriceCoefficient;
    }

    @Decimal
    public long getStopOrderPriceCoefficient() {
        return stopOrderPriceCoefficient;
    }

    public void setStopOrderPriceCoefficient(@Decimal long stopOrderPriceCoefficient) {
        this.stopOrderPriceCoefficient = stopOrderPriceCoefficient;
    }
}
