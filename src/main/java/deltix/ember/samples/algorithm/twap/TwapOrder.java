package deltix.ember.samples.algorithm.twap;

import deltix.anvil.util.CharSequenceParser;
import deltix.anvil.util.TypeConstants;
import deltix.anvil.util.annotation.Duration;
import deltix.anvil.util.annotation.Timestamp;
import deltix.anvil.util.clock.EpochClock;
import deltix.anvil.util.parser.TimestampParser;
import deltix.anvil.util.timer.Timer;
import deltix.anvil.util.timer.TimerCallback;
import deltix.anvil.util.timer.TimerJob;
import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.ember.message.trade.CustomAttribute;
import deltix.ember.message.trade.OrderEntryRequest;
import deltix.ember.message.trade.OrderEvent;
import deltix.ember.service.algorithm.AlgoOrder;
import deltix.ember.service.algorithm.util.OrderAttributesParser;
import deltix.ember.service.valid.InvalidOrderException;
import deltix.util.collections.generated.ObjectList;

class TwapOrder extends AlgoOrder {
    static final long ONE_LOT = Decimal64Utils.ONE;

    /// region Input Parameters
    static final int START_TIME_ATTRIBUTE_KEY = 6021;
    static final int END_TIME_ATTRIBUTE_KEY = 6022;
    static final int DURATION_ATTRIBUTE_KEY = 6002;
    static final int DRIP_PERCENTAGE_ATTRIBUTE_KEY = 6023;
    static final int ACTIVE_TOLERANCE_PERCENTAGE = 6024;

    @Timestamp
    long startTime = TypeConstants.TIMESTAMP_NULL;

    @Timestamp
    long endTime = TypeConstants.TIMESTAMP_NULL;

    @Duration
    long duration = TypeConstants.TIMESTAMP_NULL;

    /**
     * Percentage used to split time and quantity. For example value "5" will split BUY 200 order into 20 clips of size 10.
     */
    double dripPercentage;

    /**
     * Percentage of order qty that is allowed to be active in market at any given time. Next clip that breaches this tolerance, goes to market. Zero disables this check.
     */
    double activeTolerancePercentage;

    /// endregion

    /// region State

    private TimerJob nextSliceTask;
    /**
     * State variable: current clip number [1... 100/dripPercentage]
     */
    private int clipNo;

    void nextClip() {
        clipNo++;
    }

    /// endregion State

    void copyExtraAttributes(OrderEntryRequest request, boolean modify, EpochClock clock) {
        // parse parameters
        if (request.hasAttributes()) {
            ObjectList<CustomAttribute> attributes = request.getAttributes();
            for (int i = 0, size = attributes.size(); i < size; i++) {
                CustomAttribute attribute = attributes.get(i);
                switch (attribute.getKey()) {
                    case START_TIME_ATTRIBUTE_KEY:
                        startTime = TimestampParser.parseTimestamp(attribute.getValue());
                        break;
                    case END_TIME_ATTRIBUTE_KEY:
                        endTime = TimestampParser.parseTimestamp(attribute.getValue());
                        break;
                    case DRIP_PERCENTAGE_ATTRIBUTE_KEY:
                        dripPercentage = CharSequenceParser.parseDouble(attribute.getValue());
                        break;
                    case DURATION_ATTRIBUTE_KEY:
                        duration = OrderAttributesParser.parseDuration(DURATION_ATTRIBUTE_KEY, attribute.getValue());
                        break;
                    case ACTIVE_TOLERANCE_PERCENTAGE:
                        activeTolerancePercentage = CharSequenceParser.parseDouble(attribute.getValue());
                        break;
                }
            }
        }

        if (startTime == TypeConstants.TIMESTAMP_NULL)
            startTime = clock.time();



        if (endTime == TypeConstants.TIMESTAMP_NULL) {
            if (duration == TypeConstants.TIMESTAMP_NULL)
                throw new InvalidOrderException("Missing end time or duration parameters");
            endTime = startTime + duration;
        }


        // Validate parameters
        if (dripPercentage == 0)
            throw new InvalidOrderException("Parameter dripPercentage is missing");
        if (startTime >= endTime)
            throw new InvalidOrderException("Empty time range");
        if (activeTolerancePercentage != 0 && activeTolerancePercentage < dripPercentage)
            throw new InvalidOrderException("Parameters activeTolerancePercentage < dripPercentage");

    }


    /**
     * @return size of next clip (next child order). Result will be less than minimum lot size of no order should be sent during current period
     */
    @Decimal
    long getNextClipSize() {
        @Decimal long quantityReadyForMarket = Decimal64Utils.min(Decimal64Utils.fromDouble(Decimal64Utils.toDouble(getWorkingQuantity()) * clipNo * dripPercentage / 100), getWorkingQuantity());
        @Decimal long result = Decimal64Utils.subtract(quantityReadyForMarket, Decimal64Utils.add(getTotalExecutedQuantity(), getRemainingChildrenQuantity()));

        if (activeTolerancePercentage > 0) {
            @Decimal long maxChildQuantity = Decimal64Utils.fromDouble(Decimal64Utils.toDouble(getWorkingQuantity()) * activeTolerancePercentage);
            result = Decimal64Utils.min(result, maxChildQuantity);
        }
        return result;
    }

    int getNumberOfClips() {
        return (int) Math.ceil(100.0 / dripPercentage);
    }

    @Timestamp
    long getInterval() {
        return (endTime - startTime) / getNumberOfClips();
    }

    /**
     * @return timestamp when next clip (next child order) should be submitted; or TimerCallback.DO_NOT_RESCHEDULE if time is out
     */
    @Timestamp
    long getNextClipTime() {
        @Timestamp long result = startTime + clipNo * getInterval();
        return (result < endTime) ? result : TimerCallback.DO_NOT_RESCHEDULE;
    }

    @Override
    public void onDeactivate(OrderEvent finalEvent) {
        super.onDeactivate(finalEvent);
            cancelNextSliceTask();
    }

    @Override
    public void clear() {
        super.clear();
        assert nextSliceTask == null : "timer";
        clipNo = 0;
        nextSliceTask = null;
        startTime = TypeConstants.TIMESTAMP_NULL;
        endTime = TypeConstants.TIMESTAMP_NULL;
        duration = TypeConstants.TIMESTAMP_NULL;
        dripPercentage = 0;
        activeTolerancePercentage = 0;
    }

    void scheduleNextSliceTask(Timer timer, TimerCallback<TwapOrder> callback) {
        @Timestamp long nextSliceTime = getNextClipTime();
        assert nextSliceTime <= endTime;
        nextSliceTask = timer.schedule(nextSliceTime, callback, this);
    }

    private void cancelNextSliceTask() {
        if (nextSliceTask != null) {
            nextSliceTask.cancel();
            nextSliceTask = null;
        }
    }
}
