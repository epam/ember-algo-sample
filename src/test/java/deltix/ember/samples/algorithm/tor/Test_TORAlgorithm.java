package deltix.ember.samples.algorithm.tor;

import deltix.ember.message.trade.OrderType;
import deltix.ember.message.trade.Side;
import deltix.ember.message.trade.TimeInForce;
import deltix.ember.service.algorithm.SingleLegExecutionAlgoUnitTest;
import org.junit.Test;

public class Test_TORAlgorithm extends SingleLegExecutionAlgoUnitTest<TORAlgorithm> {

    public Test_TORAlgorithm() {
    }

    @Override
    protected TORAlgorithm createAlgorithm() {
        TORAlgorithm algorithm = new TORAlgorithm(getAlgorithmContext(), getCacheSettings());
        return algorithm;
    }

    @Test
    public void testDayOrderRejected() {
        simulateNewOrderRequest().quantity("250").orderType(OrderType.LIMIT).limitPrice("1.2").timeInForce(TimeInForce.DAY);
        verifyOrderRejectEvent("orderId:Parent#1", "reason:Only IOC orders are accepted");
    }

    @Test
    public void testNoExternalMarkets() {
        simulateOrderBook(
                "---------------"
        );
        simulateNewMarketOrderRequest().side(Side.BUY).quantity("10");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyOrderCancelEvent("orderId:Parent#1"); // order can't be matched - cancel immediately
    }

    @Test
    public void testAskQuoteSelection() {
        simulateOrderBook("50 @ 21.00",
                "40 @ 21.00",
                "10 @ 20.00",
                "20 @ 20.00",
                "---------------",
                "50 @ 10.00"
        );

        simulateNewMarketOrderRequest().side(Side.BUY).quantity("40").trader("buyer");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:20", "exchangeId:CME", "quoteId:Quote#4");
        verifyNewOrderRequest("orderId:Child#2", "quantity:10", "exchangeId:CME", "quoteId:Quote#3");
        verifyNewOrderRequest("orderId:Child#3", "quantity:10", "exchangeId:CME", "quoteId:Quote#1");
    }

    @Test
    public void testBidQuoteSelection() {
        simulateOrderBook(
                "50 @ 20.00",
                "---------------",
                "20 @ 10.00",
                "10 @ 10.00",
                "40 @ 9.00",
                "50 @ 9.00"
        );

        simulateNewMarketOrderRequest().quantity("-40").trader("seller");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:20", "exchangeId:CME", "quoteId:Quote#2");
        verifyNewOrderRequest("orderId:Child#2", "quantity:10", "exchangeId:CME", "quoteId:Quote#3");
        verifyNewOrderRequest("orderId:Child#3", "quantity:10", "exchangeId:CME", "quoteId:Quote#5");
    }
    @Test
    public void testMarketOrderFill() {
        simulateOrderBook(
                "50 @ 20.00",
                "---------------",
                "50 @ 10.00"
        );

        simulateNewMarketOrderRequest().quantity("10").trader("buyer");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:10", "exchangeId:CME", "quoteId:Quote#1");

        simulateNewMarketOrderRequest().quantity("-10").trader("seller");
        verifyOrderNewEvent("orderId:Parent#2");
        verifyNewOrderRequest("orderId:Child#2", "quantity:10", "exchangeId:CME", "quoteId:Quote#2");

        simulateTradeEvent("Child#1", "20", "10");
        verifyOrderTradeEvent("orderId:Parent#1");

        simulateTradeEvent("Child#2", "10", "10");
        verifyOrderTradeEvent("orderId:Parent#2");

        simulateOrderStatusRequest("Parent#1");
        verifyOrderStatusEvent("orderId:Parent#1", "orderStatus:COMPLETELY_FILLED");

        simulateOrderStatusRequest("Parent#2");
        verifyOrderStatusEvent("orderId:Parent#2", "orderStatus:COMPLETELY_FILLED");
    }

    /// helper methods

    private OrderRequestBuilder simulateNewMarketOrderRequest() {
        return simulateNewOrderRequest().orderType(OrderType.MARKET).timeInForce(TimeInForce.IMMEDIATE_OR_CANCEL);
    }
}