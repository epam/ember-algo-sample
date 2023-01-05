package deltix.ember.samples.algorithm.arbitrage;

import deltix.anvil.util.codec.AlphanumericCodec;
import deltix.ember.message.smd.InstrumentType;
import deltix.ember.message.trade.OrderType;
import deltix.ember.service.algorithm.SingleLegExecutionAlgoUnitTest;
import org.junit.Test;

import java.time.Duration;

public class Test_ArbitrageAlgorithm extends SingleLegExecutionAlgoUnitTest<ArbitrageAlgorithm> {

    protected static final long ENTRY_EXCHANGE = AlphanumericCodec.encode("JUMP");
    protected static final long EXIT_EXCHANGE = AlphanumericCodec.encode("CME");

    public Test_ArbitrageAlgorithm() {
        super("BTCUSD", InstrumentType.FX);
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

    // Verify that algorithm does not process inbound orders
    @Test
    public void testOrderRequestRejected() {
        simulateNewOrderRequest().quantity("250").orderType(OrderType.LIMIT).limitPrice("1.2");
        verifyOrderRejectEvent("orderId:Parent#1", "reason:Algorithm does not process trading requests");
    }

    // When entry order is issued algorithm stops monitoring
    // When it is canceled algorithm goes back to monitoring and issues another entry order on trade
    @Test
    public void testEntryOrderCancel() {
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
        verifyNewOrderRequest("orderId:Child#1", "quantity:100", "limitPrice:1.8", "side:BUY", "destinationId:JUMP", "timeInForce:IMMEDIATE_OR_CANCEL");

        simulateTimeAdvance(Duration.ofMillis(1));
        simulateMarketFeedTrade(symbol, EXIT_EXCHANGE, "1.86", "100");
        verifyNoMessagesFromAlgorithm();

        simulateOrderCancelEvent("Child#1");
        verifyNoMessagesFromAlgorithm();

        simulateMarketFeedTrade(symbol, EXIT_EXCHANGE, "1.8", "100");
        verifyNoMessagesFromAlgorithm();

        simulateMarketFeedTrade(symbol, EXIT_EXCHANGE, "1.84", "100");
        verifyNewOrderRequest("orderId:Child#2", "quantity:100", "limitPrice:1.8", "side:BUY", "destinationId:JUMP", "timeInForce:IMMEDIATE_OR_CANCEL");
    }

    // When entry order is rejected algorithm goes back to monitoring
    @Test
    public void testEntryOrderReject() {
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
        verifyNewOrderRequest("orderId:Child#1", "quantity:100", "limitPrice:1.8", "side:BUY", "destinationId:JUMP", "timeInForce:IMMEDIATE_OR_CANCEL");

        simulateOrderRejectEvent("Child#1");
        verifyNoMessagesFromAlgorithm();

        simulateMarketFeedTrade(symbol, EXIT_EXCHANGE, "1.84", "100");
        verifyNewOrderRequest("orderId:Child#2", "quantity:100", "limitPrice:1.8", "side:BUY", "destinationId:JUMP", "timeInForce:IMMEDIATE_OR_CANCEL");
    }

    // When entry order is canceled after partial fill algorithm will issue exit order for filled quantity only
    // When exit exchange market moves away after partial exit order fill algorithm issues replace request
    @Test
    public void testEntryOrderPartialFill() {
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
        verifyNewOrderRequest("orderId:Child#1", "quantity:100", "limitPrice:1.8", "side:BUY", "destinationId:JUMP", "timeInForce:IMMEDIATE_OR_CANCEL");

        simulateTradeEvent("Child#1", "1.8", "55");
        simulateOrderCancelEvent("Child#1");
        verifyNewOrderRequest("orderId:Child#2", "quantity:55", "limitPrice:1.85", "side:SELL", "destinationId:CME", "timeInForce:GOOD_TILL_CANCEL");

        simulateTradeEvent("Child#2", "1.85", "25");

        simulateMarketFeedTrade(symbol, EXIT_EXCHANGE, "1.89", "100");
        verifyNoMessagesFromAlgorithm();

        simulateOrderBook(symbol, EXIT_EXCHANGE,
                "100 @ 1.84",
                "---------------",
                "100 @ 1.55");
        verifyReplaceOrderRequest("orderId:Child#3", "originalOrderId:Child#2", "limitPrice:1.84", "quantity:55");
    }

    // Verify exit exchange best ask price is used for exit order and exit order is updated when market moves away
    // Next replace event is issued only after 100 ms and only after previous replace request is completed
    @Test
    public void testExitOrderPriceChasing() {
        simulateOrderBook(symbol, ENTRY_EXCHANGE,
                "100 @ 1.8",
                "---------------",
                "100 @ 1.5");
        simulateTimeAdvance(Duration.ofMillis(1));
        simulateMarketFeedTrade(symbol, EXIT_EXCHANGE, "1.9", "100");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100", "limitPrice:1.8", "side:BUY", "destinationId:JUMP");

        simulateOrderBook(symbol, EXIT_EXCHANGE,
                "100 @ 1.85",
                "---------------",
                "100 @ 1.55");

        simulateTradeEvent("Child#1", "1.8", "100");
        verifyNewOrderRequest("orderId:Child#2", "quantity:100", "limitPrice:1.85", "side:SELL", "destinationId:CME");

        simulateTradeEvent("Child#2", "1.85", "10");

        simulateOrderBook(symbol, EXIT_EXCHANGE,
                "100 @ 1.9",
                "---------------",
                "100 @ 1.55");
        verifyNoMessagesFromAlgorithm();

        simulateOrderBook(symbol, EXIT_EXCHANGE,
                "100 @ 1.84",
                "---------------",
                "100 @ 1.55");
        verifyReplaceOrderRequest("orderId:Child#3", "originalOrderId:Child#2", "limitPrice:1.84", "quantity:100");
        simulateOrderReplaceEvent("Child#2", "Child#3");

        simulateTimeAdvance(Duration.ofMillis(50));
        simulateOrderBook(symbol, EXIT_EXCHANGE,
                "100 @ 1.83",
                "---------------",
                "100 @ 1.55");
        verifyNoMessagesFromAlgorithm();

        simulateTimeAdvance(Duration.ofMillis(50));
        simulateOrderBook(symbol, EXIT_EXCHANGE,
                "100 @ 1.83",
                "---------------",
                "100 @ 1.55");
        verifyReplaceOrderRequest("orderId:Child#4", "originalOrderId:Child#3", "limitPrice:1.83", "quantity:100");

        simulateTimeAdvance(Duration.ofMillis(200));
        simulateOrderBook(symbol, EXIT_EXCHANGE,
                "100 @ 1.82",
                "---------------",
                "100 @ 1.55");
        verifyNoMessagesFromAlgorithm(); // no chasing while replace is already in progress

        simulateOrderReplaceEvent("Child#3", "Child#4");
        simulateTradeEvent("Child#4", "1.83", "90");
    }

    // Verify that entry BUY and exit SELL orders are issued when price difference is detected on entry and exit exchanges
    // and the last trade price is used for exit order when there is no market
    @Test
    public void testPriceWithNoExitMarket() {
        for (int i = 1; i < 5; i+=2) {
            String bidPrice = String.valueOf(i);
            String askPrice = String.valueOf(i + 0.1);
            String tradePrice = String.valueOf(i + 0.2);

            simulateOrderBook(symbol, ENTRY_EXCHANGE,
                    "100 @ " + askPrice,
                    "---------------",
                    "100 @ " + bidPrice);
            simulateTimeAdvance(Duration.ofMillis(1));
            simulateMarketFeedTrade(symbol, EXIT_EXCHANGE, tradePrice, "100");

            String entryChild = "Child#" + i;
            verifyNewOrderRequest("orderId:" + entryChild, "quantity:100", "limitPrice:" + askPrice, "side:BUY", "destinationId:JUMP");
            simulateTradeEvent(entryChild, askPrice, "100");

            String exitChild = "Child#" + (i+1);
            verifyNewOrderRequest("orderId:" + exitChild, "quantity:100", "limitPrice:" + tradePrice, "side:SELL", "destinationId:CME");
            simulateTradeEvent(exitChild, tradePrice, "100");

            verifyNoMessagesFromAlgorithm();
        }
    }

    // When exit order is rejected remain in exiting state but stop chasing
    @Test
    public void testExitOrderRejected() {
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
        verifyNewOrderRequest("orderId:Child#1", "quantity:100", "limitPrice:1.8", "side:BUY", "destinationId:JUMP", "timeInForce:IMMEDIATE_OR_CANCEL");

        simulateTradeEvent("Child#1", "1.8", "100");
        verifyNewOrderRequest("orderId:Child#2", "quantity:100", "limitPrice:1.85");

        simulateOrderRejectEvent("Child#2");

        simulateOrderBook(symbol, EXIT_EXCHANGE,
                "100 @ 1.82",
                "---------------",
                "100 @ 1.55");
        verifyNoMessagesFromAlgorithm();
    }

    // When exit order is canceled remain in exiting state but stop chasing
    @Test
    public void testExitOrderCanceled() {
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
        verifyNewOrderRequest("orderId:Child#1", "quantity:100", "limitPrice:1.8", "side:BUY", "destinationId:JUMP", "timeInForce:IMMEDIATE_OR_CANCEL");

        simulateTradeEvent("Child#1", "1.8", "100");
        verifyNewOrderRequest("orderId:Child#2", "quantity:100", "limitPrice:1.85");

        simulateOrderCancelEvent("Child#2");

        simulateOrderBook(symbol, EXIT_EXCHANGE,
                "100 @ 1.82",
                "---------------",
                "100 @ 1.55");
        verifyNoMessagesFromAlgorithm();
    }

}
