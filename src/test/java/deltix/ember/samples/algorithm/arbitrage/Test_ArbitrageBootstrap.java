package deltix.ember.samples.algorithm.arbitrage;

import deltix.anvil.util.codec.AlphanumericCodec;
import deltix.ember.message.smd.InstrumentType;
import deltix.ember.message.trade.OrderType;
import deltix.ember.message.trade.Side;
import deltix.ember.message.trade.TimeInForce;
import deltix.ember.service.algorithm.SingleLegExecutionAlgoUnitTest;
import org.junit.Test;

import java.time.Duration;
import java.util.Collections;

public class Test_ArbitrageBootstrap extends SingleLegExecutionAlgoUnitTest<ArbitrageAlgorithm> {
    protected static final long ENTRY_EXCHANGE = AlphanumericCodec.encode("JUMP");
    protected static final long EXIT_EXCHANGE = AlphanumericCodec.encode("CME");

    public Test_ArbitrageBootstrap() {
        super("BTCUSD", InstrumentType.FX);
        setAutomateSomeAttrs(false);
        setMakeLeaderOnStart(false);
    }

    @Override
    protected ArbitrageAlgorithm createAlgorithm() {
        ArbitrageAlgorithmFactory factory = new ArbitrageAlgorithmFactory();
        factory.setEntryExchange(AlphanumericCodec.encode("JUMP"));
        factory.setExitExchange(AlphanumericCodec.encode("CME"));

        ArbitrageAlgorithm algorithm = factory.create(getAlgorithmContext());
        defineFutureInstrument(symbol, algorithm);
        return algorithm;
    }

    @Test
    public void testActiveEntryOrder() {
        simulateOrderBook(symbol, ENTRY_EXCHANGE,
                "100 @ 1.8",
                "---------------",
                "100 @ 1.5");
        simulateOrderBook(symbol, EXIT_EXCHANGE,
                "100 @ 1.85",
                "---------------",
                "100 @ 1.55");
        simulateTimeAdvance(Duration.ofMillis(1));
        simulateMarketFeedTrade(symbol, EXIT_EXCHANGE, "1.85", "100");

        // simulate outbound order
        simulateNewOrderRequest("Child#0").destinationId("JUMP").quantity("100").side(Side.BUY).orderType(OrderType.LIMIT).limitPrice("1.8").timeInForce(TimeInForce.IMMEDIATE_OR_CANCEL).flush();
        simulateOrderNewEvent("Child#0");

        // Entry order was active before the failover
        makeLeader();

        simulateTimeAdvance(Duration.ofMillis(10));

        // verify there is no monitoring at this point
        simulateMarketFeedTrade(symbol, EXIT_EXCHANGE, "1.85", "100");

        simulateTradeEvent("Child#0", "1.8", "100");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100", "limitPrice:1.85", "side:SELL", "destinationId:CME");

        simulateTradeEvent("Child#1", "1.85", "100");

        // verify in monitoring state after exit order was filled
        simulateMarketFeedTrade(symbol, EXIT_EXCHANGE, "1.85", "100");
        verifyNewOrderRequest("side:BUY", "quantity:100", "limitPrice:1.8");
    }

    @Test
    public void testActiveExitOrder() {
        simulateOrderBook(symbol, ENTRY_EXCHANGE,
                "100 @ 1.8",
                "---------------",
                "100 @ 1.5");
        simulateOrderBook(symbol, EXIT_EXCHANGE,
                "100 @ 1.85",
                "---------------",
                "100 @ 1.55");
        simulateTimeAdvance(Duration.ofMillis(1));
        simulateMarketFeedTrade(symbol, EXIT_EXCHANGE, "1.85", "100");

        // simulate outbound order
        simulateNewOrderRequest("Child#0").destinationId("JUMP").quantity("100").side(Side.BUY).orderType(OrderType.LIMIT).limitPrice("1.8").timeInForce(TimeInForce.IMMEDIATE_OR_CANCEL).flush();
        simulateOrderNewEvent("Child#0");
        simulateTradeEvent("Child#0", "1.8", "100");

        simulateTimeAdvance(Duration.ofMillis(1));
        simulateNewOrderRequest("Child#00").destinationId("CME").quantity("100").side(Side.SELL).orderType(OrderType.LIMIT).limitPrice("1.85").timeInForce(TimeInForce.GOOD_TILL_CANCEL).flush();
        simulateOrderNewEvent("Child#00");

        // Exit order was active before the failover
        makeLeader();

        // verify there is no monitoring at this point
        simulateMarketFeedTrade(symbol, EXIT_EXCHANGE, "1.85", "100");

        simulateTimeAdvance(Duration.ofMillis(10));
        simulateTradeEvent("Child#00", "1.85", "100");

        // verify in monitoring state after exit order was filled
        simulateMarketFeedTrade(symbol, EXIT_EXCHANGE, "1.85", "100");
        verifyNewOrderRequest("side:BUY", "quantity:100", "limitPrice:1.8");
    }

}