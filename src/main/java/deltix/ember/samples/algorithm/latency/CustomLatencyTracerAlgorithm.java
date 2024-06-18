package deltix.ember.samples.algorithm.latency;

import deltix.ember.message.trade.OrderTradeReportEvent;
import deltix.ember.service.algorithm.AlgoOrder;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.ChildOrder;
import deltix.ember.service.algorithm.SimpleAlgorithm;

import java.time.Duration;

public class CustomLatencyTracerAlgorithm extends SimpleAlgorithm {

    private final SimpleLatencyTracer fillLatencyTracer;

    CustomLatencyTracerAlgorithm(AlgorithmContext context, Duration latencyStatPeriod) {
        super(context);

        fillLatencyTracer = new SimpleLatencyTracer (
                context.getName() + "FillLatency", // prefix that will be used for telemetry counters
                context.getClock(),
                context.getCounterFactory(),
                latencyStatPeriod,
                Duration.ofMillis(50), // unreasonable max
                10, // skip first N evens for warmup
                100 // stop logging errors after first 100
                );

        // tracer wills start publishing latency histogram as ember metric at configurable interval
        fillLatencyTracer.schedule(getTimer());
    }


    @Override
    protected void handleTradeEvent(ChildOrder<AlgoOrder> order, OrderTradeReportEvent event) {

        fillLatencyTracer.addRecord(
                event.getTimestampNs(), // when FILL event entered Trade Connector (epoch time in nanos)
                currentTimeNs()         // current time (epoch time in nanos)
        );

        super.handleTradeEvent(order, event);

    }
}
