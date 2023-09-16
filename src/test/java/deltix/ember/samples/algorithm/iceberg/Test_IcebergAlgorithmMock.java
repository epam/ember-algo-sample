package deltix.ember.samples.algorithm.iceberg;


import deltix.anvil.util.codec.AlphanumericCodec;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.ember.message.smd.InstrumentAttribute;
import deltix.ember.message.smd.InstrumentType;
import deltix.ember.message.smd.MutableCurrencyUpdate;
import deltix.ember.message.smd.MutableInstrumentAttribute;
import deltix.ember.message.trade.OrderType;
import deltix.ember.service.algorithm.Algorithm;
import deltix.ember.service.algorithm.MarketSubscription;
import deltix.ember.service.algorithm.SingleLegExecutionAlgoUnitTest;
import deltix.util.collections.generated.ObjectArrayList;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("Duplicates")
public class Test_IcebergAlgorithmMock extends SingleLegExecutionAlgoUnitTest<IcebergAlgorithm> {

    public Test_IcebergAlgorithmMock () {
        super("BTC/USD", InstrumentType.FX);
    }

    @Override
    protected IcebergAlgorithm createAlgorithm() {
        return new IcebergAlgorithm(getAlgorithmContext(), getCacheSettings(), 1);
    }

    @Override
    protected void initAlgorithm(IcebergAlgorithm algorithm) {
        super.initAlgorithm(algorithm);
        defineFutureInstrument(symbol, algorithm);
    }

    // One of the tests uses synthetic instrument = the simplest way to hack subscription is this:
    @Override
    protected MarketSubscription getAlgorithmMarketSubscription() {
        MarketSubscription subscription = mock(MarketSubscription.class);
        when(subscription.isSubscribedToAll()).thenReturn(true); // we are testing different instruments
        when(subscription.isSubscribedToAllInstruments()).thenReturn(true); // we are testing different instruments
        return subscription;
    }

    private void defineCurrencyInstrument(String symbol, Algorithm algorithm, double tick, String orderSizePrecision, String minOrderSize) {
        MutableCurrencyUpdate currency = new MutableCurrencyUpdate();
        currency.setSymbol(symbol);
        currency.setInstrumentType(InstrumentType.FX);
        currency.setPriceIncrement(Decimal64Utils.fromDouble(tick));

        ObjectArrayList<InstrumentAttribute> attributes = new ObjectArrayList<InstrumentAttribute>();
        MutableInstrumentAttribute instrumentAttribute = new MutableInstrumentAttribute();
        instrumentAttribute.setKey("orderSizePrecision");
        instrumentAttribute.setValue(orderSizePrecision);
        attributes.add(instrumentAttribute);

        instrumentAttribute = new MutableInstrumentAttribute();
        instrumentAttribute.setKey("minOrderSize");
        instrumentAttribute.setValue(minOrderSize);
        attributes.add(instrumentAttribute);

        currency.setAttributes(attributes);
        algorithm.onCurrencyUpdate(currency);
    }

    @Test
    public void checkLimitOrdersAreAccepted() {
        simulateNewOrderRequest().quantity("250").displayQty("50").orderType(OrderType.LIMIT).limitPrice("1.2");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:50");
    }

    @Test
    public void checkCustomOrdersAreAccepted() {
        simulateNewOrderRequest().quantity("250").displayQty("50").orderType(OrderType.CUSTOM).limitPrice("1.2");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:50");
    }

    @Test
    public void checkOrdersWithoutLimitPriceAreRejected() {
        simulateNewOrderRequest().quantity("250").displayQty("50").orderType(OrderType.CUSTOM).limitPrice(Decimal64Utils.NULL);
        verifyOrderRejectEvent("orderId:Parent#1");
    }

    /**
     * We submit iceberg and it sits on the market (without fills) we use order status to verify
     */
    @Test
    public void standingOrder() {
        simulateNewOrderRequest().quantity("250").displayQty("100").limitPrice("1.2");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");

        simulateOrderStatusRequest("Parent#1");
        verifyOrderStatusEvent("orderId:Parent#1", "orderStatus:NEW", "quantity:250");
    }

    /**
     * Normal order execution: ICEBERG for 250/100 is filled using 3 child orders
     */
    @Test
    public void normalCompleteFill() {
        simulateNewOrderRequest().quantity("250").displayQty("100").limitPrice("123");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");

        simulateTradeEvent("Child#1", "123", "100");
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:100");
        verifyNewOrderRequest("orderId:Child#2", "quantity:100");

        simulateTradeEvent("Child#2", "123", "100");
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:100");
        verifyNewOrderRequest("orderId:Child#3", "quantity:50");

        simulateTradeEvent("Child#3", "123", "50");
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:50", "tradePrice:123");
    }

    /**
     * Same as above but child orders are partially filled.
     */
    @Test
    public void normalPartialFill() {
        simulateNewOrderRequest().quantity("150").displayQty("100").limitPrice("123");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");
        simulateTradeEvent("Child#1", "123", "50"); // fill 50 out of 100
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:50");
        verifyNewOrderRequest("orderId:Child#2", "quantity:50");

        simulateTradeEvent("Child#1", "123", "50"); // fill remaining 50 on the first child
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:50");

        simulateTradeEvent("Child#2", "123", "50");
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:50");
    }

    /**
     * Partially filled order is cancelled. We ensure that active children are cancelled too.
     */
    @Test
    public void partialFillThenCancel() {
        simulateNewOrderRequest().quantity("300").displayQty("100").limitPrice("1.2");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");
        simulateTradeEvent("Child#1", "123", "100");
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:100");
        verifyNewOrderRequest("orderId:Child#2", "quantity:100");

        simulateCancelRequest("Parent#1");

        verifyCancelOrderRequest("orderId:Child#2", "sourceId:" + ALGORITHM_NAME);
        verifyOrderPendingCancelEvent("orderId:Parent#1", "reason:JUnit test cancel");

        simulateOrderCancelEvent("Child#2");
        verifyOrderCancelEvent("orderId:Parent#1", "reason:JUnit test cancel");
    }

    /**
     * The very first child order rejected: ICEBERG is cancelled
     */
    @Test
    public void childRejection1() {
        simulateNewOrderRequest().quantity("250").displayQty("100").limitPrice("1.2");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");

        simulateOrderRejectEvent("Child#1");
        verifyOrderCancelEvent("orderId:Parent#1", "reason:Unexpected reject of child order");
    }

    /**
     * Second child order rejected: ICEBERG is cancelled
     */
    @Test
    public void childRejection2() {
        simulateNewOrderRequest().quantity("250").displayQty("100").limitPrice("1.2");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");

        simulateTradeEvent("Child#1", "123", "100");
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:100");
        verifyNewOrderRequest("orderId:Child#2", "quantity:100");

        simulateOrderRejectEvent("Child#2");
        verifyOrderCancelEvent("orderId:Parent#1", "reason:Unexpected reject of child order");
    }

    /**
     * Here we simply cancel active ICEBERG order
     */
    @Test
    public void simpleCancel() {
        simulateNewOrderRequest().quantity("1000").displayQty("100").limitPrice("1.2");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");

        simulateCancelRequest("Parent#1");  // 5 second timeout

        verifyCancelOrderRequest("orderId:Child#1", "sourceId:" + ALGORITHM_NAME);
        verifyOrderPendingCancelEvent("orderId:Parent#1", "reason:JUnit test cancel");

        simulateOrderCancelEvent("Child#1");
        verifyOrderCancelEvent("orderId:Parent#1", "reason:JUnit test cancel");
    }

    /**
     * Here we modifying order price. Iceberg Algo is supposed cancel outstanding child orders except the oldest and modify price on the remaining child order
     */
    @Test
    public void modifyPrice() {
        simulateNewOrderRequest().quantity("200").displayQty("100").limitPrice("12.345");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");

        simulateTradeEvent("Child#1", "123", "50"); // partial fill 50/100: we want to test two active child orders
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:50");
        verifyNewOrderRequest("orderId:Child#2", "quantity:50");

        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("12.500"); // change price 12.345 => 12.500

        // verify that the last child is cancelled and the oldest child has its price and size modified
        verifyCancelOrderRequest("orderId:Child#2");
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");

        simulateOrderCancelEvent("Child#2");
        // child id is "4" instead of "3" because child cancel request consumed ID "3"
        // quantity is 150 because 50 is already filled
        verifyReplaceOrderRequest("originalOrderId:Child#1", "orderId:Child#4", "limitPrice:12.5", "quantity:150");  // note child new size exceeds display size

        simulateOrderReplaceEvent("Child#1", "Child#4");
        verifyOrderReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");
    }

    /**
     * Same as above but this time as we try to cancel children, one of cancel will be rejected (due to fill)
     */
    @Test
    public void fillWhileModify() {
        simulateNewOrderRequest().quantity("200").displayQty("100").limitPrice("12.345");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");

        simulateTradeEvent("Child#1", "123", "50"); // partial fill 50/100
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:50");
        verifyNewOrderRequest("orderId:Child#2", "quantity:50");

        simulateTradeEvent("Child#1", "123", "25"); // another partial fill 25/100
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:25");
        verifyNewOrderRequest("orderId:Child#3", "quantity:25");

        // at this moment we have three child orders on the market (algo is partially filled 75 out of 200)

        simulateReplaceOrderRequest().quantity("200").displayQty("100").limitPrice("12.500"); // change price 12.345 => 12.500

        verifyCancelOrderRequest("orderId:Child#2");
        verifyCancelOrderRequest("orderId:Child#3");
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");

        // but in the middle of PendingReplace we have lots of fills:
        simulateTradeEvent("Child#1", "123", "50"); // fill of remaining amount
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:50");
        simulateTradeEvent("Child#2", "123", "10"); // partial fill
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:10");
        simulateTradeEvent("Child#3", "123", "10"); // partial fill
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:10");

        // one child is cancelled OK
        simulateOrderCancelEvent("Child#2");
        simulateOrderCancelEvent("Child#3");

        // The key of this test is to ensure that cancel-reject allow us to leave PreparingReplace state
        verifyOrderReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");

        // total size of parent order is 200. We had fills: 50+25+50+10+10 = 145. Remaining parent quantity is 55
        verifyNewOrderRequest("orderId:Child#6", /*"parentOrderId:Parent#2",*/ "quantity:55"); // parentOrderId was removed from API
    }

    /**
     * Same as above but this time as we try to cancel children, one of cancel will be rejected (due to fill)
     */
    @Test
    public void modifyPriceReject() {
        simulateNewOrderRequest().quantity("200").displayQty("100").limitPrice("12.345");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");

        simulateTradeEvent("Child#1", "123", "50"); // partial fill 50/100
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:50");
        verifyNewOrderRequest("orderId:Child#2", "quantity:50");

        simulateTradeEvent("Child#1", "123", "25"); // another partial fill 25/100
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:25");
        verifyNewOrderRequest("orderId:Child#3", "quantity:25");

        // at this moment we have three child orders on the market (algo is partially filled 75 out of 200)

        simulateReplaceOrderRequest().quantity("200").displayQty("100").limitPrice("12.500"); // change price 12.345 => 12.500

        // verify that the last child is cancelled and the oldest child has its price and size modified
        verifyCancelOrderRequest("orderId:Child#2");
        verifyCancelOrderRequest("orderId:Child#3");
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");

        // one child is cancelled OK
        simulateOrderCancelEvent("Child#2");

        // but another gets a complete fill and cancel reject
        simulateTradeEvent("Child#3", "123", "25"); // will be a complete fill for this child
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:25");

        verifyReplaceOrderRequest("originalOrderId:Child#1", "orderId:Child#6", "limitPrice:12.5", "quantity:100");

        simulateOrderCancelRejectEvent("Child#3");
        //Simplified: rest of the test removed
    }

    /**
     * Here we increasing order size from 200 to 500 and ensure that ICEBERG order continues to work after we filled 200
     */
    @Test
    public void increaseQuantity() {
        simulateNewOrderRequest().quantity("200").displayQty("100").limitPrice("1.2");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");

        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("1.2");
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");
        verifyOrderReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");

        simulateTradeEvent("Child#1", "123", "100");
        verifyOrderTradeEvent("orderId:Parent#2", "tradeQuantity:100");
        verifyNewOrderRequest("orderId:Child#2", "quantity:100");

        simulateTradeEvent("Child#2", "123", "100");
        verifyOrderTradeEvent("orderId:Parent#2", "tradeQuantity:100");
        verifyNewOrderRequest("orderId:Child#3", "quantity:100");

        simulateTradeEvent("Child#3", "123", "50");
        verifyOrderTradeEvent("orderId:Parent#2", "tradeQuantity:50");
        verifyNewOrderRequest("orderId:Child#4", "quantity:50");
        // .... we can see that replacement quantity took effect
    }

    /**  */
    @Test
    public void decreaseQuantity() {
        simulateNewOrderRequest().quantity("200").displayQty("100").limitPrice("1.2");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");

        simulateReplaceOrderRequest().quantity("100").displayQty("50").orderType(OrderType.LIMIT);  // decrease display size 100=>50 and total quantity 200=>100
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");
        verifyReplaceOrderRequest("originalOrderId:Child#1", "orderId:Child#2", "quantity:50"); // make sure child's quantity is reduced

        //...
    }

    /**
     * Display size is increased after first child order is placed. Making sure that child orders reflect the change.
     */
    @Test
    public void modifyDisplayQty() {
        simulateNewOrderRequest().quantity("200").displayQty("50").limitPrice("1.2");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:50");

        simulateReplaceOrderRequest().quantity("200").displayQty("100").limitPrice("1.2"); // modify display size 50->100
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");
        verifyOrderReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");
        verifyNewOrderRequest("orderId:Child#2", "quantity:50");
        //...
    }

    /**
     * Same as above but here we change price and display size - we should see child replacement request
     */
    @Test
    public void modifyDisplayQtyAndPrice() {
        simulateNewOrderRequest().quantity("200").displayQty("50").limitPrice("12.300");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:50");

        simulateReplaceOrderRequest().quantity("200").displayQty("100").limitPrice("12.345"); // modify display size 50->100, also CHANGE PRICE

        verifyOrderPendingReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");
        verifyReplaceOrderRequest("originalOrderId:Child#1", "orderId:Child#2", "quantity:100");

        simulateOrderReplaceEvent("Child#1", "Child#2");
        verifyOrderReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");

        //...
    }

    /**
     * In this test we validate that when unspecified Display Quantity equals to to entire order size, even if order is subsequently modified (and size increased)
     */
    @Test
    public void modifyQty() {
        simulateNewOrderRequest().quantity("200").limitPrice("1.2");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:200");

        simulateReplaceOrderRequest().quantity("250").limitPrice("1.2"); // modify display size 50->100
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");
        verifyOrderReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");
        verifyNewOrderRequest("orderId:Child#2", "quantity:50");
    }

    /**
     * Same as above but we decrease total order size and verify that child order is reduced too
     */
    @Test
    public void defaultDisplayQtyOrderModified() {
        simulateNewOrderRequest().quantity("200").limitPrice("1.2");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:200");

        simulateReplaceOrderRequest().quantity("200").displayQty("50").limitPrice("1.2"); // this should reduce child order size 200->50
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");
        verifyReplaceOrderRequest("originalOrderId:Child#1", "quantity:50");
        simulateOrderReplaceEvent("Child#1", "Child#2");
        verifyOrderReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");
    }

    /**
     * In this case contract is multi-leg (calendar spread) we check how fills reported for individual leg is handled
     */
    @Test
    public void multiLegContractFill() {


        defineSyntheticInstrument("ESZ7M8", algorithm);

        simulateNewOrderRequest().symbol("ESZ7M8").quantity("100").limitPrice("1.05");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");

        // legs reported first
        simulate(symbol(makeTradeEvent("Child#1", "123", "50"), "ESZ7"));
        verifyOrderTradeEvent("orderId:Parent#1", "symbol:ESZ7", "tradeQuantity:50", "tradePrice:123");
        simulate(symbol(makeTradeEvent("Child#1", "124", "50"), "ESM8"));
        verifyOrderTradeEvent("orderId:Parent#1", "symbol:ESM8", "tradeQuantity:50", "tradePrice:124");
        simulate(symbol(makeTradeEvent("Child#1", "1.04", "50"), "ESZ7M8"));
        verifyOrderTradeEvent("orderId:Parent#1", "symbol:ESZ7M8", "tradeQuantity:50", "tradePrice:1.04");

        // synthetic reported first
        simulate(makeTradeEvent("Child#1", "1.04", "50"));
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:50", "tradePrice:1.04"); // assuming ESZ7M8
        simulate(symbol(makeTradeEvent("Child#1", "123", "50"), "ESZ7"));
        verifyOrderTradeEvent("orderId:Parent#1", "symbol:ESZ7", "tradeQuantity:50", "tradePrice:123");
        simulate(symbol(makeTradeEvent("Child#1", "124", "50"), "ESM8"));
        verifyOrderTradeEvent("orderId:Parent#1", "symbol:ESM8", "tradeQuantity:50", "tradePrice:124");
    }

    /**
     * Simple replace test: here we just replace a price of order while there is only one child order at the market
     */
    @Test
    public void testReplaceDuringNormalStage() {
        simulateNewOrderRequest().quantity("200").displayQty("100").limitPrice("12.345");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");

        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("12.500"); // change price 12.345 => 12.500
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");
        verifyReplaceOrderRequest("originalOrderId:Child#1", "orderId:Child#2", "limitPrice:12.5", "quantity:100");

        simulateOrderReplaceEvent("Child#1", "Child#2");
        verifyOrderReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");
    }

    /**
     * Same as testReplaceDuringNormalStage() but this time order is pending cancellation
     */
    @Test
    public void testReplaceDuringPreparingCancelStage() {
        simulateNewOrderRequest().quantity("200").displayQty("100").limitPrice("12.345");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");

        simulateCancelRequest("Parent#1");
        verifyCancelOrderRequest("orderId:Child#1");
        verifyOrderPendingCancelEvent("orderId:Parent#1");

        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("12.500");
        verifyOrderReplaceRejectEvent("originalOrderId:Parent#1", "orderId:Parent#2", "reason:Cancel pending");

        simulateOrderCancelEvent("Child#1");
        verifyOrderCancelEvent("orderId:Parent#1");
    }

    /**
     * In this negative test we are trying to cancel already finished order
     */
    @Test
    public void testReplaceDuringFinishedStage() {
        simulateNewOrderRequest().quantity("200").displayQty("100").limitPrice("12.345");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");

        simulateCancelRequest("Parent#1");
        verifyCancelOrderRequest("orderId:Child#1");
        verifyOrderPendingCancelEvent("orderId:Parent#1");

        simulateOrderCancelEvent("Child#1");
        verifyOrderCancelEvent("orderId:Parent#1");

        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("12.500");
        verifyOrderReplaceRejectEvent("originalOrderId:Parent#1", "orderId:Parent#2", "reason:Order already complete");
    }

    /**
     * Test what happens when user sends multiple replacement requests, all during PrepareReplace stage
     */
    @Test
    public void testReplaceDuringPrepareReplacementStage() {
        simulateNewOrderRequest().quantity("200").displayQty("100").limitPrice("12.345");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");

        // in order to enter PreparingReplace stage we need two child orders
        // lets simulate a fill so that algo will place a second child order
        simulateTradeEvent("Child#1", "123", "50"); // partial fill 50/100: we want to test two active child orders
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:50");
        verifyNewOrderRequest("orderId:Child#2", "quantity:50");

        // Replace#1
        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("12.500"); // change price 12.345 => 12.500

        // verify that the last child is cancelled and the oldest child has its price and size modified
        verifyCancelOrderRequest("orderId:Child#2");
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");

        // Replace #2: now that we entered PreparingReplace stage let's send another replace request
        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("12.505"); // change price 12.500 => 12.505
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#2", "orderId:Parent#3");

        // confirm child cancellation (to leave PreparingReplace stage into CommittingReplace)
        simulateOrderCancelEvent("Child#2");

        verifyReplaceOrderRequest("originalOrderId:Child#1", "orderId:Child#4", "limitPrice:12.505", "quantity:150");  // note child new size exceeds display size

        simulateOrderReplaceEvent("Child#1", "Child#4");
        verifyOrderReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");
        verifyOrderReplaceEvent("originalOrderId:Parent#2", "orderId:Parent#3");
    }


    /**
     * Same as testReplaceDuringPrepareReplacementStage but we send 5 replacements!
     */
    @Test
    public void testReplaceDuringPrepareReplacementStage5() {
        simulateNewOrderRequest().quantity("200").displayQty("100").limitPrice("12.345");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");

        // in order to enter PreparingReplace stage we need two child orders
        // lets simulate a fill so that algo will place a second child order
        simulateTradeEvent("Child#1", "123", "50"); // partial fill 50/100: we want to test two active child orders
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:50");
        verifyNewOrderRequest("orderId:Child#2", "quantity:50");

        // Replace#1
        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("12.500"); // change price 12.345 => 12.500

        // verify that the last child is cancelled and the oldest child has its price and size modified
        verifyCancelOrderRequest("orderId:Child#2");
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");

        // Replace #2: now that we entered PreparingReplace stage let's send another replace request
        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("12.510"); // change price 12.500 => 12.510
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#2", "orderId:Parent#3");

        // Replace #3: now that we entered PreparingReplace stage let's send another replace request
        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("12.515"); // change price 12.510 => 12.515
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#3", "orderId:Parent#4");

        // Replace #4: now that we entered PreparingReplace stage let's send another replace request
        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("12.520"); // change price 12.515 => 12.520
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#4", "orderId:Parent#5");

        // Replace #5: now that we entered PreparingReplace stage let's send another replace request
        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("12.525"); // change price 12.520 => 12.525
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#5", "orderId:Parent#6");


        // confirm child cancellation (to leave PreparingReplace stage into CommittingReplace)
        simulateOrderCancelEvent("Child#2");

        verifyReplaceOrderRequest("originalOrderId:Child#1", "orderId:Child#4", "limitPrice:12.525", "quantity:150");  // note child new size exceeds display size

        simulateOrderReplaceEvent("Child#1", "Child#4");
        verifyOrderReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");
        verifyOrderReplaceEvent("originalOrderId:Parent#2", "orderId:Parent#3");
        verifyOrderReplaceEvent("originalOrderId:Parent#3", "orderId:Parent#4");
        verifyOrderReplaceEvent("originalOrderId:Parent#4", "orderId:Parent#5");
        verifyOrderReplaceEvent("originalOrderId:Parent#5", "orderId:Parent#6");
    }

    /**
     * Same as testReplaceDuringNormalStage() above but this time we receive two replacement requests
     */
    @Test
    public void testReplaceDuringCommitReplacementStage() {
        simulateNewOrderRequest().quantity("200").displayQty("100").limitPrice("12.345");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");

        // Replace#1
        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("12.500"); // change price 12.345 => 12.500
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");
        verifyReplaceOrderRequest("originalOrderId:Child#1", "orderId:Child#2", "limitPrice:12.5", "quantity:100");

        // Replace #2
        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("12.505"); // change price 12.500 => 12.505
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#2", "orderId:Parent#3");

        // ack child replace:
        simulateOrderReplaceEvent("Child#1", "Child#2");
        verifyOrderReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");

        verifyReplaceOrderRequest("originalOrderId:Child#2", "orderId:Child#3", "limitPrice:12.505", "quantity:100");

        simulateOrderReplaceEvent("Child#2", "Child#3");
        verifyOrderReplaceEvent("originalOrderId:Parent#2", "orderId:Parent#3");
    }

    /**
     * Same as testReplaceDuringCommitReplacementStage() above but first replacement request is rejected
     */
    @Test
    public void testReplaceRejectDuringCommitReplacementStage() {
        simulateNewOrderRequest().quantity("200").displayQty("100").limitPrice("12.345");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");

        // Replace#1
        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("12.500"); // change price 12.345 => 12.500
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");
        verifyReplaceOrderRequest("originalOrderId:Child#1", "orderId:Child#2", "limitPrice:12.5", "quantity:100");

        // Replace #2
        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("12.505"); // change price 12.500 => 12.505
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#2", "orderId:Parent#3");

        // NACK child replace:
        simulateOrderReplaceRejectEvent("Child#1", "Child#2");
        verifyOrderReplaceRejectEvent("originalOrderId:Parent#1", "orderId:Parent#2");
        verifyOrderReplaceRejectEvent("originalOrderId:Parent#1", "orderId:Parent#3");
    }


    /**
     * Same as testReplaceDuringCommitReplacementStage() but here we receive cancel request while second replace request is pending
     */
    @Test
    public void testCancelDuringCommitReplacementStage() {
        simulateNewOrderRequest().quantity("200").displayQty("100").limitPrice("12.345");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");

        // Replace#1
        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("12.500"); // change price 12.345 => 12.500
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");
        verifyReplaceOrderRequest("originalOrderId:Child#1", "orderId:Child#2", "limitPrice:12.5", "quantity:100");

        // Replace #2
        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("12.505"); // change price 12.500 => 12.505
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#2", "orderId:Parent#3");

        // Cancel parent
        simulateCancelRequest("Parent#1");
        verifyCancelOrderRequest("orderId:Child#1");
        verifyOrderPendingCancelEvent("orderId:Parent#1");

        // ack child replace:
        simulateOrderReplaceEvent("Child#1", "Child#2");
        verifyOrderReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");

        simulateOrderCancelEvent("Child#1");
        verifyOrderReplaceRejectEvent("originalOrderId:Parent#2", "orderId:Parent#3");
        verifyOrderCancelEvent("orderId:Parent#2");
    }

    /**
     * Fairly simple test - we get a reject on attempt to modify child order
     */
    @Test
    public void testChildReplaceReject() {
        simulateNewOrderRequest().quantity("200").displayQty("100").limitPrice("12.345");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");

        // Replace#1
        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("12.500"); // change price 12.345 => 12.500
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");
        verifyReplaceOrderRequest("originalOrderId:Child#1", "orderId:Child#2", "limitPrice:12.5", "quantity:100");

        // And now a reject :-)
        simulateOrderReplaceRejectEvent("Child#1", "Child#2");
        verifyOrderReplaceRejectEvent("originalOrderId:Parent#1", "orderId:Parent#2");

        simulateOrderStatusRequest("Parent#1");
        verifyOrderStatusEvent("orderId:Parent#1", "orderStatus:NEW", "quantity:200");

    }

    /**
     * Same as above but there are two replacement requests pending
     */
    @Test
    public void testChildReplaceReject2() {
        simulateNewOrderRequest().quantity("200").displayQty("100").limitPrice("12.345");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:100");

        // Replace#1
        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("12.500"); // change price 12.345 => 12.500
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#1", "orderId:Parent#2");
        verifyReplaceOrderRequest("originalOrderId:Child#1", "orderId:Child#2", "limitPrice:12.5", "quantity:100");

        // Replace #2
        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("12.505"); // change price 12.500 => 12.505
        verifyOrderPendingReplaceEvent("originalOrderId:Parent#2", "orderId:Parent#3");

        // And now a reject :-)
        simulateOrderReplaceRejectEvent("Child#1", "Child#2");
        verifyOrderReplaceRejectEvent("originalOrderId:Parent#1", "orderId:Parent#2");
        verifyOrderReplaceRejectEvent("originalOrderId:Parent#1", "orderId:Parent#3");

        simulateOrderStatusRequest("Parent#1");
        verifyOrderStatusEvent("orderId:Parent#1", "orderStatus:NEW", "quantity:200");
    }

    /**
     * Cancel arrives for already filled order (must be rejected)
     */
    @Test
    public void cancelOfAlreadyCompletedOrder() {
        simulateNewOrderRequest().quantity("200").limitPrice("12.345");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:200");

        simulateTradeEvent("Child#1", "123.3449", "200");
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:200");

        simulateOrderStatusRequest("Parent#1");
        verifyOrderStatusEvent("orderId:Parent#1", "orderStatus:COMPLETELY_FILLED", "cumulativeQuantity:200");

        simulateCancelRequest("Parent#1");
        verifyOrderCancelRejectEvent("orderId:Parent#1", "reason:Order is complete");
    }

    /**
     * Replace arrives for already filled order (must be rejected)
     */
    @Test
    public void replaceOfAlreadyCompletedOrder() {
        simulateNewOrderRequest().quantity("200").limitPrice("12.345");
        verifyOrderNewEvent("orderId:Parent#1");
        verifyNewOrderRequest("orderId:Child#1", "quantity:200");

        simulateTradeEvent("Child#1", "123.3449", "200");
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:200");

        simulateOrderStatusRequest("Parent#1");
        verifyOrderStatusEvent("orderId:Parent#1", "orderStatus:COMPLETELY_FILLED", "cumulativeQuantity:200");

        simulateReplaceOrderRequest().quantity("500").displayQty("100").limitPrice("12.500");
        verifyOrderReplaceRejectEvent("originalOrderId:Parent#1", "orderId:Parent#2", "reason:Order already complete");
    }

    @Test
    public void testUnknownOrder() {
        simulateOrderStatusRequest("Child#Unknown");
        verifyOrderStatusEvent("orderId:Child#Unknown", "orderUnknown:true");
    }

    @Test
    public void supportFractionalQuantities() {
        defineCurrencyInstrument(symbol, algorithm, 0.01, "3", "0");

        simulateMarket(
                symbol,
                AlphanumericCodec.encode("BITFINEX"),
                "30 @ 0.0787",
                "---------------",
                "30 @ 0.0783"
        );

        simulateNewOrderRequest().symbol(symbol).quantity("2.1555").limitPrice("0.0785");
        verifyOrderNewEvent("orderId:Parent#1");

        verifyNewOrderRequest("orderId:Child#1", "limitPrice:0.0785", "quantity:2.155");

        simulateTradeEvent("Child#1", "0.0785", "2.155");
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:2.155");

        verifyOrderCancelEvent("orderId:Parent#1", "reason:Invalid order size (< minimum order size).");
        verifyNoMessagesFromAlgorithm();
    }

    @Test
    /*
    Send order with size that less than min order size
     */
    public void supportFractionalQuantities2() {
        defineCurrencyInstrument(symbol, algorithm, 0.01, "3", "1");

        simulateMarket(
                symbol,
                AlphanumericCodec.encode("BITFINEX"),
                "30 @ 0.0787",
                "---------------",
                "30 @ 0.0783"
        );

        simulateNewOrderRequest().symbol(symbol).quantity("0.95").limitPrice("0.0785");
        verifyOrderRejectEvent("orderId:Parent#1", "reason:Invalid order size (< minimum order size).");
        verifyNoMessagesFromAlgorithm();
    }

    @Test
    /*
    Send order with size 2.5 and display qty 1.Min order size is specified to 1.
    2 child orders should be send and residual 0.5 should canceled.
     */
    public void supportFractionalQuantities3() {
        defineCurrencyInstrument(symbol, algorithm, 0.01, "3", "1");

        simulateMarket(
                symbol,
                AlphanumericCodec.encode("BITFINEX"),
                "30 @ 0.0787",
                "---------------",
                "30 @ 0.0783"
        );

        simulateNewOrderRequest().symbol(symbol).quantity("2.5").limitPrice("0.0785").displayQty("1");

        verifyOrderNewEvent("orderId:Parent#1");

        verifyNewOrderRequest("orderId:Child#1", "quantity:1");
        simulateTradeEvent("Child#1", "0.0785", "1");
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:1");

        verifyNewOrderRequest("orderId:Child#2", "quantity:1");
        simulateTradeEvent("Child#2", "0.0785", "1");
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:1");

        verifyOrderCancelEvent("orderId:Parent#1", "reason:Invalid order size (< minimum order size).");
        verifyNoMessagesFromAlgorithm();
    }

    @Test
    /*
    Send normal order and then replace it with order where qtu less than min order qty
     */
    public void supportFractionalQuantitiesReplaceTest() {

        defineCurrencyInstrument(symbol, algorithm, 0.01, "3", "1");

        simulateMarket(
                symbol,
                AlphanumericCodec.encode("BITFINEX"),
                "30 @ 0.0787",
                "---------------",
                "30 @ 0.0783"
        );

        simulateNewOrderRequest().symbol(symbol).quantity("2.5").limitPrice("0.0785").displayQty("1");

        verifyOrderNewEvent("orderId:Parent#1");

        verifyNewOrderRequest("orderId:Child#1", "quantity:1");
        simulateTradeEvent("Child#1", "0.0785", "1");
        verifyOrderTradeEvent("orderId:Parent#1", "tradeQuantity:1");

        verifyNewOrderRequest("orderId:Child#2", "quantity:1");

        simulateReplaceOrderRequest().quantity("0.9").limitPrice("0.0785"); // change qty 1 to 0.9 (less than min order size)

        verifyOrderReplaceRejectEvent("originalOrderId:Parent#1", "orderId:Parent#2", "reason:Invalid order size (< minimum order size).");

        verifyNoMessagesFromAlgorithm();
    }
}
