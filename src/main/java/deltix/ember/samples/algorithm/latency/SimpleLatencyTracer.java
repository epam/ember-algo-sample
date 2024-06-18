package deltix.ember.samples.algorithm.latency;

import com.epam.deltix.gflog.api.Log;
import com.epam.deltix.gflog.api.LogFactory;
import deltix.anvil.util.TypeConstants;
import deltix.anvil.util.annotation.Timestamp;
import deltix.anvil.util.clock.EpochClock;
import deltix.anvil.util.counter.Counter;
import deltix.anvil.util.counter.CounterFactory;
import deltix.anvil.util.counter.ParentCounterFactory;
import deltix.anvil.util.timer.Timer;
import deltix.anvil.util.timer.TimerCallback;
import deltix.anvil.util.timer.TimerJob;
import org.HdrHistogram.Histogram;

import javax.annotation.Nonnull;
import java.time.Duration;

public class SimpleLatencyTracer {
    private static final Log LOG = LogFactory.getLog(SimpleLatencyTracer.class);
    private final long maxDelayNs;
    private final Counter totalCounter;
    private final Counter minCounter;
    private final Counter pStatTimestamp;
    private final Counter p50Counter;
    private final Counter p99Counter;
    private final Counter p999Counter;
    private final Counter p9999Counter;
    private final Counter p99999Counter;
    private final Counter p100Counter;
    private final Counter errorCounter;
    private final Histogram histogram; // in nanos
    private final long statIntervalMillis;
    @Timestamp
    private long nextStatTime;
    private final int maxErrorLog;
    private int warmupCount;
    private int errorCount;
    private int prevErrorCount;


    public SimpleLatencyTracer(String id, EpochClock clock, CounterFactory counterFactory, Duration intervalBetweenStatDumps, Duration maxExpectedLatency, int maxErrorLog, int warmupCount) {
        assert intervalBetweenStatDumps != null && intervalBetweenStatDumps.toMillis() > 0;
        assert maxExpectedLatency != null && maxExpectedLatency.toNanos() > 0; // should we throw here instead of assert?

        this.maxDelayNs = maxExpectedLatency.toNanos();
        this.errorCount = 0;
        this.warmupCount = warmupCount;
        this.maxErrorLog = maxErrorLog;
        this.histogram = new Histogram(maxDelayNs, 3);

        counterFactory = new ParentCounterFactory(id + "Lat", counterFactory);
        this.totalCounter = counterFactory.newCounter("Total");
        this.minCounter = counterFactory.newCounter("Min");
        this.p50Counter = counterFactory.newCounter("P50");
        this.p99Counter = counterFactory.newCounter("P99");
        this.p999Counter = counterFactory.newCounter("P999");
        this.p9999Counter = counterFactory.newCounter("P9999");
        this.p99999Counter = counterFactory.newCounter("P99999");
        this.p100Counter = counterFactory.newCounter("P100");
        this.errorCounter = counterFactory.newCounter("Errors");
        this.pStatTimestamp = counterFactory.newCounter("Timestamp");

        this.statIntervalMillis = intervalBetweenStatDumps.toMillis();
        this.nextStatTime = clock.time() + 5 * statIntervalMillis;
    }


    public void addRecord(long signalOriginTimeNs, long signalTimeNs) {
        long differenceNs = signalTimeNs - signalOriginTimeNs;
        if (differenceNs < 0) {
            if (errorCount < maxErrorLog)
                LOG.error("Negative signal processing time: %s ns").with(differenceNs);
            errorCount++;
        } else {
            if (differenceNs > maxDelayNs) {
                if (warmupCount > 0) {
                    warmupCount--;
                } else {
                    if (errorCount < maxErrorLog)
                        LOG.error("Signal processing time exceeds configured maximum: %s ns").with(differenceNs);
                }
                errorCount++;
                differenceNs = maxDelayNs;
            }
            histogram.recordValue(differenceNs);
        }
    }


    private void publishStats(@Timestamp long now) {
        pStatTimestamp.setWeak(now);
        final long totalCount = histogram.getTotalCount();
        totalCounter.setWeak(totalCount);
        if (totalCount > 0) {
            minCounter.setWeak(histogram.getValueAtPercentile(0));
            p50Counter.setWeak(histogram.getValueAtPercentile(50));
            p99Counter.setWeak(histogram.getValueAtPercentile(99));
            p999Counter.setWeak(histogram.getValueAtPercentile(99.9));
            p9999Counter.setWeak(histogram.getValueAtPercentile(99.99));
            p99999Counter.setWeak(histogram.getValueAtPercentile(99.999));
            p100Counter.setWeak(histogram.getValueAtPercentile(100));
            histogram.reset();
        } else {
            minCounter.setWeak(TypeConstants.LONG_NULL);
            p50Counter.setWeak(TypeConstants.LONG_NULL);
            p99Counter.setWeak(TypeConstants.LONG_NULL);
            p999Counter.setWeak(TypeConstants.LONG_NULL);
            p9999Counter.setWeak(TypeConstants.LONG_NULL);
            p99999Counter.setWeak(TypeConstants.LONG_NULL);
            p100Counter.setWeak(TypeConstants.LONG_NULL);
        }
        errorCounter.setWeak(errorCount - prevErrorCount);
        prevErrorCount = errorCount;
    }

    /**
     * Timer's processing routine.
     *
     * @param currentTime    current time (maybe slightly in the past)
     * @param tracer pass-through parameter passed to {@link Timer#schedule(long, TimerCallback, Object)}
     * @return Next invocation timestamp or {@link TimerCallback#DO_NOT_RESCHEDULE} to prevent task to be rescheduled.
     * @throws IllegalArgumentException if returned time is not in the future (strictly after time specified by 'currentTime' parameter)
     */
    @Timestamp
    private static long onTimer(@Timestamp long currentTime, SimpleLatencyTracer tracer) {
        assert tracer != null;
        tracer.publishStats(currentTime);
        return currentTime + tracer.statIntervalMillis;
    }

    public TimerJob schedule(@Nonnull Timer timer) {
        return timer.schedule(nextStatTime, SimpleLatencyTracer::onTimer, this);
    }
}
