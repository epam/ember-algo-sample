package deltix.ember.samples.algorithm.signaltrader;

import com.epam.deltix.dfp.Decimal64Utils;
import deltix.ember.message.trade.OrderType;
import deltix.ember.service.algorithm.SingleLegExecutionAlgoUnitTest;
import org.junit.Test;

public class Test_SignalTraderAlgorithm extends SingleLegExecutionAlgoUnitTest<SignalTraderAlgorithm> {

    private double numStdDevs = 2.0;
    private int numOfPeriods = 10;
    private long enterOrderSizeCoeff = 2;
    private double exitOrderSizeCoeff = 0.05;
    private double stopOrderSizeCoeff = 0.05;

    public Test_SignalTraderAlgorithm() {
    }

    @Override
    protected SignalTraderAlgorithm createAlgorithm() {
        SignalTraderSettings settings = new SignalTraderSettings();
        settings.setNumStdDevs(numStdDevs);
        settings.setNumPeriods(numOfPeriods);
        settings.setEnterOrderSizeCoefficient(Decimal64Utils.fromLong(enterOrderSizeCoeff));
        settings.setExitOrderPriceCoefficient(Decimal64Utils.fromDouble(exitOrderSizeCoeff));
        settings.setStopOrderPriceCoefficient(Decimal64Utils.fromDouble(stopOrderSizeCoeff));


        SignalTraderAlgorithm algorithm = new SignalTraderAlgorithm(getAlgorithmContext(), getCacheSettings(), settings);
        defineFutureInstrument(symbol, algorithm);
        return algorithm;
    }

    /** Verify that algorithm does not process inbound orders */
    @Test
    public void testOrderRequestRejected() {
        simulateNewOrderRequest().quantity("250").orderType(OrderType.LIMIT).limitPrice("1.2");
        verifyOrderRejectEvent("orderId:Parent#1", "reason:Algorithm does not process trading requests");
    }

    /** Verify that enter SELL and exit BUY orders are issued when the market goes up and down */
    @Test
    public void testExitBuyOrderIssued() {
        triggerGoShortSignal();
        verifyNewOrderRequest("orderId:Child#1", "quantity:6", "limitPrice:2", "side:SELL"); // Enter order
        simulateTradeEvent("Child#1", "2", "6");

        verifyNewOrderRequest("orderId:Child#2", "quantity:6", "limitPrice:1.9", "side:BUY"); // Exit order
        verifyNewOrderRequest("orderId:Child#3", "quantity:6", "limitPrice:2.1", "side:BUY"); // Stop order
    }

    /** Verify that enter BUY and exit SELL orders are issued when the market goes down and up */
    @Test
    public void testExitSellOrderIssued() {
        triggerGoLongSignal();
        verifyNewOrderRequest("orderId:Child#1", "quantity:6", "limitPrice:1", "side:BUY"); // Enter order
        simulateTradeEvent("Child#1", "1", "6");

        verifyNewOrderRequest("orderId:Child#2", "quantity:6", "limitPrice:1.05", "side:SELL"); // Exit order
        verifyNewOrderRequest("orderId:Child#3", "quantity:6", "limitPrice:0.95", "side:SELL"); // Stop order
    }

    /** Verify that complete fill of EXIT order causes cancellation of STOP order */
    @Test
    public void testExitOrderCompleteFill() {
        triggerGoLongSignal();
        verifyNewOrderRequest("orderId:Child#1", "quantity:6", "limitPrice:1", "side:BUY"); // Enter order
        simulateTradeEvent("Child#1", "1", "6");

        verifyNewOrderRequest("orderId:Child#2", "quantity:6", "limitPrice:1.05", "side:SELL"); // Exit order
        verifyNewOrderRequest("orderId:Child#3", "quantity:6", "limitPrice:0.95", "side:SELL"); // Stop order

        simulateTradeEvent("Child#2", "1.05", "6");

        // verify that algo cancels Stop order
        verifyCancelOrderRequest("orderId:Child#3");
    }


    /** Verify that a partial fill of the EXIT order causes reduction of size in STOP order */
     @Test
     public void testExitOrderPartialFill() {
         triggerGoLongSignal();
         verifyNewOrderRequest("orderId:Child#1", "quantity:6", "limitPrice:1", "side:BUY"); // Enter order
         simulateTradeEvent("Child#1", "1", "6");

         verifyNewOrderRequest("orderId:Child#2", "quantity:6", "limitPrice:1.05", "side:SELL"); // Exit order
         verifyNewOrderRequest("orderId:Child#3", "quantity:6", "limitPrice:0.95", "side:SELL"); // Stop order

         simulateTradeEvent("Child#2", "1.05", "2"); // 2 out of 6

         // verify that reduces cancels Stop order
         verifyReplaceOrderRequest("originalOrderId:Child#3", "quantity:4");
     }

     /** Verify that complete fill of STOP order causes cancellation of EXIT order */
    @Test
    public void testStopOrderCompleteFill() {
        triggerGoLongSignal();
        verifyNewOrderRequest("orderId:Child#1", "quantity:6", "limitPrice:1", "side:BUY"); // Enter order
        simulateTradeEvent("Child#1", "1", "6");

        verifyNewOrderRequest("orderId:Child#2", "quantity:6", "limitPrice:1.05", "side:SELL"); // Exit order
        verifyNewOrderRequest("orderId:Child#3", "quantity:6", "limitPrice:0.95", "side:SELL"); // Stop order

        simulateTradeEvent("Child#3", "0.95", "6");

        // verify that algo cancels Exit order
        verifyCancelOrderRequest("orderId:Child#2");
    }

    /** Verify that a partial fill of the STOP order causes reduction of size in EXIT order */
    @Test
    public void testStopOrderPartialFill() {
        triggerGoLongSignal();
        verifyNewOrderRequest("orderId:Child#1", "quantity:6", "limitPrice:1", "side:BUY"); // Enter order
        simulateTradeEvent("Child#1", "1", "6");

        verifyNewOrderRequest("orderId:Child#2", "quantity:6", "limitPrice:1.05", "side:SELL"); // Exit order
        verifyNewOrderRequest("orderId:Child#3", "quantity:6", "limitPrice:0.95", "side:SELL"); // Stop order

        simulateTradeEvent("Child#3", "0.95", "2"); // 2 out of 6

        // verify that algo reduces Exit order
        verifyReplaceOrderRequest("originalOrderId:Child#2", "quantity:4");
    }

    /** Same as above but Stop order experiences multiple fills */
    @Test
    public void testReplacementChain() {
        triggerGoLongSignal();
        verifyNewOrderRequest("orderId:Child#1", "quantity:6", "limitPrice:1", "side:BUY"); // Enter order
        simulateTradeEvent("Child#1", "1", "6");

        verifyNewOrderRequest("orderId:Child#2", "quantity:6", "limitPrice:1.05", "side:SELL"); // Exit order
        verifyNewOrderRequest("orderId:Child#3", "quantity:6", "limitPrice:0.95", "side:SELL"); // Stop order

        simulateTradeEvent("Child#3", "0.95", "2"); // STOP: 2 out of 6
        verifyReplaceOrderRequest("originalOrderId:Child#2", "orderId:Child#4", "quantity:4"); // reduce EXIT 6->4

        simulateTradeEvent("Child#3", "0.95", "2"); // STOP: 4 out of 6
        verifyReplaceOrderRequest("originalOrderId:Child#4", "orderId:Child#5", "quantity:2"); // reduce EXIT 6->4->2

        simulateTradeEvent("Child#3", "0.95", "2"); // STOP: completely filled
        verifyCancelOrderRequest("orderId:Child#5");
    }


    /** Variation of the same test, this time we receive replace ACKs for EXIT order modifications */
    @Test
    public void testReplacementChain1() {
        triggerGoLongSignal();
        verifyNewOrderRequest("orderId:Child#1", "quantity:6", "limitPrice:1", "side:BUY"); // Enter order
        simulateTradeEvent("Child#1", "1", "6");

        verifyNewOrderRequest("orderId:Child#2", "quantity:6", "limitPrice:1.05", "side:SELL"); // Exit order
        verifyNewOrderRequest("orderId:Child#3", "quantity:6", "limitPrice:0.95", "side:SELL"); // Stop order

        simulateTradeEvent("Child#3", "0.95", "2"); // STOP: 2 out of 6
        verifyReplaceOrderRequest("originalOrderId:Child#2", "orderId:Child#4", "quantity:4"); // reduce EXIT 6->4
        simulateOrderReplaceEvent("Child#2", "Child#4");

        simulateTradeEvent("Child#3", "0.95", "2"); // STOP: 4 out of 6
        verifyReplaceOrderRequest("originalOrderId:Child#4", "orderId:Child#5", "quantity:2"); // reduce EXIT 6->4->2
        simulateOrderReplaceEvent("Child#4", "Child#5");

        simulateTradeEvent("Child#3", "0.95", "2"); // STOP: completely filled
        verifyCancelOrderRequest("orderId:Child#5");
        simulateOrderCancelEvent("Child#5");
    }

    /** Variation of the same test, this time we partially fill STOP and then EXIT orders */
    @Test
    public void testReplacementChain2() {
        triggerGoLongSignal();
        verifyNewOrderRequest("orderId:Child#1", "quantity:6", "limitPrice:1", "side:BUY"); // Enter order
        simulateTradeEvent("Child#1", "1", "6");

        verifyNewOrderRequest("orderId:Child#2", "quantity:6", "limitPrice:1.05", "side:SELL"); // Exit order
        verifyNewOrderRequest("orderId:Child#3", "quantity:6", "limitPrice:0.95", "side:SELL"); // Stop order

        simulateTradeEvent("Child#3", "0.95", "2"); // STOP: 2 out of 6
        verifyReplaceOrderRequest("originalOrderId:Child#2", "orderId:Child#4", "quantity:4"); // reduce EXIT 6->4

        // Let's imagine market shoot up and the "take profit" EXIT order fully filled (This is exceptional situation)
        simulateTradeEvent("Child#2", "1.05", "6"); // NOTE This will produce overfill !! We are 2 contracts short
        // algorithm should attempt to cancel remaining part of STOP order
        verifyCancelOrderRequest("orderId:Child#3");

        // algorithm should fix overfill using MARKET BUY order
        verifyNewOrderRequest("orderId:Child#6", "quantity:2", "orderType:MARKET", "side:BUY"); // Exit order

        simulateOrderReplaceRejectEvent("Child#2", "Child#4");

        simulateOrderCancelEvent("Child#3");
    }


    // Helpers


    private void triggerGoShortSignal() {
        for (int i = 0; i < numOfPeriods; i++) {
            simulateMarketFeedTrade(1.0, 3);
        }
        simulateMarketFeedTrade(2.0, 3);
    }

    private void triggerGoLongSignal() {
        for (int i = 0; i < numOfPeriods; i++) {
            simulateMarketFeedTrade(2.0, 3);
        }
        simulateMarketFeedTrade(1.0, 3);
    }


}