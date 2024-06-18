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
                10,
                100
                );

        fillLatencyTracer.schedule(getTimer());
    }


    @Override
    protected void handleTradeEvent(ChildOrder<AlgoOrder> order, OrderTradeReportEvent event) {
        super.handleTradeEvent(order, event);

        fillLatencyTracer.addRecord(
                event.getTimestampNs(), // when FILL event entered Trade Connector
                currentTimeNs()         // current time
        );
    }
}
