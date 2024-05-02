package deltix.ember.samples.algorithm.marketmaker;

import deltix.anvil.util.codec.AlphanumericCodec;
import deltix.ember.message.smd.InstrumentType;
import deltix.ember.message.trade.OrderType;
import deltix.ember.service.algorithm.SingleLegExecutionAlgoUnitTest;
import org.junit.Test;

import java.time.Duration;

public class Test_MarketMakerAlgorithm extends SingleLegExecutionAlgoUnitTest<MarketMakerAlgorithm> {

    protected static final long EXCHANGE = AlphanumericCodec.encode("JUMP");
    protected static final long SOURCE_EXCHANGE = AlphanumericCodec.encode("CME");
    protected static final double[] SELL_QUOTE_SIZES = new double[] {150};
    protected static final double[] BUY_QUOTE_SIZES = new double[] {20, 30, 100};
    protected static final double[] SELL_MARGINS = new double[] {80};
    protected static final double[] BUY_MARGINS = new double[] {30, 50, 100};
    protected static final double MIN_SPREAD = 100;
    protected static final double MIN_PRICE_CHANGE = 5;
    protected static final double POSITION_MAX_SIZE = 10;
    protected static final double POSITION_NORMAL_SIZE = 0;
    protected static final double MAX_LONG_EXPOSURE = 150;
    protected static final double MAX_SHORT_EXPOSURE = 150;

    protected static final int RATE_LIMIT = 5;

    public Test_MarketMakerAlgorithm() {
        super("BTCUSD", InstrumentType.FX);
    }

    @Override
    protected MarketMakerAlgorithm createAlgorithm() {
        MarketMakerAlgorithmFactory factory = new MarketMakerAlgorithmFactory();
        factory.setExchange(EXCHANGE);
        factory.setSourceExchange(SOURCE_EXCHANGE);
        factory.setSellQuoteSizes(SELL_QUOTE_SIZES);
        factory.setBuyQuoteSizes(BUY_QUOTE_SIZES);
        factory.setSellMargins(SELL_MARGINS);
        factory.setBuyMargins(BUY_MARGINS);
        factory.setMinSpread(MIN_SPREAD);
        factory.setMinPriceChange(MIN_PRICE_CHANGE);
        factory.setPositionNormalSize(POSITION_NORMAL_SIZE);
        factory.setPositionMaxSize(POSITION_MAX_SIZE);
        factory.setMaxLongExposure(MAX_LONG_EXPOSURE);
        factory.setMaxShortExposure(MAX_SHORT_EXPOSURE);

        factory.setRateLimit(RATE_LIMIT);

        MarketMakerAlgorithm algorithm = factory.create(getAlgorithmContext());
        defineFutureInstrument(symbol, algorithm);
        return algorithm;
    }

    // Verify that algorithm does not process inbound orders
    @Test
    public void testOrderRequestRejected() {
        simulateNewOrderRequest().quantity("250").orderType(OrderType.LIMIT).limitPrice("1.2");
        verifyOrderRejectEvent("orderId:Parent#1", "reason:Algorithm does not process trading requests");
    }

    // When our current position becomes greater than positionMaxSize hedger is triggered
    @Test
    public void testHedgerPlacesOrder() {
        simulateOrderBook(symbol, SOURCE_EXCHANGE,
                "3 @ 9000",
                "---------------",
                "1 @ 7500");

        verifyNewOrderRequest("orderId:Child#1", "quantity:150", "limitPrice:9080", "side:SELL", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        verifyNewOrderRequest("orderId:Child#2", "quantity:20", "limitPrice:7470", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        verifyNewOrderRequest("orderId:Child#3", "quantity:30", "limitPrice:7450", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        verifyNewOrderRequest("orderId:Child#4", "quantity:100", "limitPrice:7400", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");

        // hedger is not triggered yet
        simulateTimeAdvance(Duration.ofMillis(1));
        simulateTradeEvent("Child#1", "9080", "5");
        verifyNoMessagesFromAlgorithm();

        // now it is
        simulateTimeAdvance(Duration.ofMillis(1));
        simulateTradeEvent("Child#1", "9080", "15");
        verifyNewOrderRequest("orderId:Child#5", "quantity:20", "limitPrice:9000", "side:BUY", "destinationId:CME", "timeInForce:IMMEDIATE_OR_CANCEL");
    }

    // When current position is no longer exceeding position max size algo won't resend hedging orders
    @Test
    public void testHedgerStops() {
        simulateOrderBook(symbol, SOURCE_EXCHANGE,
                "3 @ 9000",
                "---------------",
                "1 @ 7500");

        verifyNewOrderRequest("orderId:Child#1", "quantity:150", "limitPrice:9080", "side:SELL", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        verifyNewOrderRequest("orderId:Child#2", "quantity:20", "limitPrice:7470", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        verifyNewOrderRequest("orderId:Child#3", "quantity:30", "limitPrice:7450", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        verifyNewOrderRequest("orderId:Child#4", "quantity:100", "limitPrice:7400", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");

        // current position will be 11, which is greater than position max size, so hedger will place an order
        simulateTimeAdvance(Duration.ofMillis(1));
        simulateTradeEvent("Child#1", "9080", "5");
        simulateTimeAdvance(Duration.ofMillis(1));
        simulateTradeEvent("Child#1", "9080", "6");
        verifyNewOrderRequest("orderId:Child#5", "quantity:11", "limitPrice:9000", "side:BUY", "destinationId:CME", "timeInForce:IMMEDIATE_OR_CANCEL");

        // after hedging order is traded it is canceled
        simulateTimeAdvance(Duration.ofMillis(1));
        simulateTradeEvent("Child#5", "9000", "3");
        simulateOrderCancelEvent("Child#5");

        // current position becomes less than threshold, so there are no new orders
        verifyNoMessagesFromAlgorithm();
    }

    // When current position still is exceeding position max size algo will resend hedging orders
    @Test
    public void testHedgerResends() {
        simulateOrderBook(symbol, SOURCE_EXCHANGE,
                "3 @ 9000",
                "---------------",
                "1 @ 7500");

        verifyNewOrderRequest("orderId:Child#1", "quantity:150", "limitPrice:9080", "side:SELL", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        verifyNewOrderRequest("orderId:Child#2", "quantity:20", "limitPrice:7470", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        verifyNewOrderRequest("orderId:Child#3", "quantity:30", "limitPrice:7450", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        verifyNewOrderRequest("orderId:Child#4", "quantity:100", "limitPrice:7400", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");

        // current position will be 15, which is greater than position max size, so hedger will place an order
        simulateTimeAdvance(Duration.ofMillis(1));
        simulateTradeEvent("Child#1", "9080", "5");
        simulateTimeAdvance(Duration.ofMillis(1000));
        simulateTradeEvent("Child#1", "9080", "10");
        verifyNewOrderRequest("orderId:Child#5", "quantity:15", "limitPrice:9000", "side:BUY", "destinationId:CME", "timeInForce:IMMEDIATE_OR_CANCEL");

        // hedging order is traded and then canceled
        simulateTimeAdvance(Duration.ofMillis(1));
        simulateTradeEvent("Child#5", "9000", "3");
        simulateOrderCancelEvent("Child#5");

        // should send new order request, because we are still above position max size
        verifyNewOrderRequest("orderId:Child#6", "quantity:12", "limitPrice:9000", "side:BUY", "destinationId:CME", "timeInForce:IMMEDIATE_OR_CANCEL");
    }

    // When receive market data where basePrice will change more than minPriceChange order replacement will be triggered
    @Test
    public void testMarketPricesChanged() {
        simulateOrderBook(symbol, SOURCE_EXCHANGE,
                "3 @ 9000",
                "---------------",
                "1 @ 7500");

        verifyNewOrderRequest("orderId:Child#1", "quantity:150", "limitPrice:9080", "side:SELL", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        verifyNewOrderRequest("orderId:Child#2", "quantity:20", "limitPrice:7470", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        verifyNewOrderRequest("orderId:Child#3", "quantity:30", "limitPrice:7450", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        verifyNewOrderRequest("orderId:Child#4", "quantity:100", "limitPrice:7400", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");

        simulateTimeAdvance(Duration.ofMillis(1000));
        simulateOrderBook(symbol, SOURCE_EXCHANGE,
                "3 @ 9080",
                "---------------",
                "1 @ 7600");

        verifyCancelOrderRequest("orderId:Child#1");
        verifyCancelOrderRequest("orderId:Child#2");
        verifyCancelOrderRequest("orderId:Child#3");
        verifyCancelOrderRequest("orderId:Child#4");

        simulateTimeAdvance(Duration.ofMillis(1000));
        simulateOrderCancelEvent("Child#1");
        verifyNewOrderRequest("orderId:Child#9", "quantity:150", "limitPrice:9160", "side:SELL", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        simulateOrderCancelEvent("Child#2");
        verifyNewOrderRequest("orderId:Child#10", "quantity:20", "limitPrice:7570", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        simulateOrderCancelEvent("Child#3");
        verifyNewOrderRequest("orderId:Child#11", "quantity:30", "limitPrice:7550", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        simulateOrderCancelEvent("Child#4");
        verifyNewOrderRequest("orderId:Child#12", "quantity:100", "limitPrice:7500", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
    }

    // When we receive OrderRejectEvent or unsolicited OrderCancelEvent we retry to send the same order again
    @Test
    public void testOrderRetry() {
        simulateOrderBook(symbol, SOURCE_EXCHANGE,
                "3 @ 9000",
                "---------------",
                "1 @ 7500");

        verifyNewOrderRequest("orderId:Child#1", "quantity:150", "limitPrice:9080", "side:SELL", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        verifyNewOrderRequest("orderId:Child#2", "quantity:20", "limitPrice:7470", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        verifyNewOrderRequest("orderId:Child#3", "quantity:30", "limitPrice:7450", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        verifyNewOrderRequest("orderId:Child#4", "quantity:100", "limitPrice:7400", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");

        simulateTimeAdvance(Duration.ofMillis(1));
        simulateOrderRejectEvent("Child#2");
        verifyNewOrderRequest("orderId:Child#5", "quantity:20", "limitPrice:7470", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");


        simulateTimeAdvance(Duration.ofMillis(1000));
        simulateOrderCancelEvent("Child#4");
        verifyNewOrderRequest("orderId:Child#6", "quantity:100", "limitPrice:7400", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
    }

    // When we hit rate limit we won't submit another request
    @Test
    public void testRateLimit() {
        simulateOrderBook(symbol, SOURCE_EXCHANGE,
                "3 @ 9000",
                "---------------",
                "1 @ 7500");

        verifyNewOrderRequest("orderId:Child#1", "quantity:150", "limitPrice:9080", "side:SELL", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        verifyNewOrderRequest("orderId:Child#2", "quantity:20", "limitPrice:7470", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        verifyNewOrderRequest("orderId:Child#3", "quantity:30", "limitPrice:7450", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        verifyNewOrderRequest("orderId:Child#4", "quantity:100", "limitPrice:7400", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");

        simulateTimeAdvance(Duration.ofMillis(1));
        simulateOrderBook(symbol, SOURCE_EXCHANGE,
                "3 @ 9080",
                "---------------",
                "1 @ 7600");

        verifyCancelOrderRequest("orderId:Child#1");
        // now we have 5 requests submitted and cannot proceed with price chasing
        verifyNoMessagesFromAlgorithm();
    }

    // When we hit maxLongExposure risk limit don't submit NewOrderRequests
    @Test
    public void testMaxLongExposure() {
        simulateOrderBook(symbol, SOURCE_EXCHANGE,
                "3 @ 9000",
                "---------------",
                "1 @ 7500");

        verifyNewOrderRequest("orderId:Child#1", "quantity:150", "limitPrice:9080", "side:SELL", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        verifyNewOrderRequest("orderId:Child#2", "quantity:20", "limitPrice:7470", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        verifyNewOrderRequest("orderId:Child#3", "quantity:30", "limitPrice:7450", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");
        verifyNewOrderRequest("orderId:Child#4", "quantity:100", "limitPrice:7400", "side:BUY", "destinationId:JUMP", "timeInForce:GOOD_TILL_CANCEL");

        simulateTimeAdvance(Duration.ofMillis(500));
        simulateTradeEvent("Child#2", "7470", "20");

        // hedger is triggered
        verifyNewOrderRequest("orderId:Child#5", "quantity:20", "limitPrice:7500", "side:SELL", "destinationId:CME", "timeInForce:IMMEDIATE_OR_CANCEL");

        // at this point we have current position 20 and openBuyQty 130
        // algo won't place a new buy order as openBuyQty + CP will exceed maxLongExposure (150)
        verifyNoMessagesFromAlgorithm();

        simulateTimeAdvance(Duration.ofMillis(1000));
        simulateTradeEvent("Child#1", "9080", "20");

        // hedging orders are not required now, we have to cancel one that is still open (20 sell)
        // note that one second is passed, so rate limiter allows next order
        verifyCancelOrderRequest("orderId:Child#5");

        simulateTimeAdvance(Duration.ofMillis(50));
        verifyNoMessagesFromAlgorithm();
    }
}
