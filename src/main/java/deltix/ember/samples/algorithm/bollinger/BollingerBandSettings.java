package deltix.ember.samples.algorithm.bollinger;

import com.epam.deltix.dfp.Decimal;


/**
 * Created by Andy on 10/23/2017.
 */
public class BollingerBandSettings {
    private double numStdDevs = 2;

    @Decimal
    private long constantCommission = 0;

    @Decimal
    private long commissionPerShare = 0;

    private double openOrderSizeCoefficient = 1000;

    private double profitPercent = 40;

    private double stopLossPercent = 40;

    private int numPeriods = 300;

    private boolean enableShort = true;

    private boolean enableLong = true;

    public double getNumStdDevs() {
        return numStdDevs;
    }

    public void setNumStdDevs(double numStdDevs) {
        this.numStdDevs = numStdDevs;
    }

    @Decimal
    public long getConstantCommission() {
        return constantCommission;
    }

    public void setConstantCommission(@Decimal long constantCommission) {
        this.constantCommission = constantCommission;
    }

    @Decimal
    public long getCommissionPerShare() {
        return commissionPerShare;
    }

    public void setCommissionPerShare(@Decimal long commissionPerShare) {
        this.commissionPerShare = commissionPerShare;
    }

    public double getOpenOrderSizeCoefficient() {
        return openOrderSizeCoefficient;
    }

    public void setOpenOrderSizeCoefficient(double openOrderSizeCoefficient) {
        this.openOrderSizeCoefficient = openOrderSizeCoefficient;
    }

    public double getProfitPercent() {
        return profitPercent;
    }

    public void setProfitPercent(double profitPercent) {
        this.profitPercent = profitPercent;
    }

    public double getStopLossPercent() {
        return stopLossPercent;
    }

    public void setStopLossPercent(double stopLossPercent) {
        this.stopLossPercent = stopLossPercent;
    }

    public int getNumPeriods() {
        return numPeriods;
    }

    public void setNumPeriods(int numPeriods) {
        this.numPeriods = numPeriods;
    }

    public boolean isEnableShort() {
        return enableShort;
    }

    public void setEnableShort(boolean enableShort) {
        this.enableShort = enableShort;
    }

    public boolean isEnableLong() {
        return enableLong;
    }

    public void setEnableLong(boolean enableLong) {
        this.enableLong = enableLong;
    }
}
