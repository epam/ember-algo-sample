---
name: algo-test
description: Write unit tests for Ember trading algorithms using the algo test framework (BaseAbstractAlgorithmTest). Use when asked to create or modify algo unit tests, add test coverage, or understand how the test framework works.
---

# Ember Algo Unit Test Framework

Tests extend `BaseAbstractAlgorithmTest<YourAlgorithm>` from `deltix.ember.service.algorithm`.

The living tutorial is at:
`ember/algo-test/src/main/java/deltix/ember/service/algorithm/samples/tutorial/Test_AlgoTestFrameworkTutorial.java`

## Minimal Test Class Structure

```java
class Test_MyAlgorithm extends BaseAbstractAlgorithmTest<MyAlgorithm> {

    @Override
    protected MyAlgorithm createAlgorithm() {
        return new MyAlgorithm(getAlgorithmContext(), getCacheSettings());
    }

    @Override
    protected void initAlgorithm(MyAlgorithm algorithm) {
        super.initAlgorithm(algorithm);
        defineSyntheticInstrument("AAPL");
        simulateMarketFeedBBO("AAPL", "149.99", 100, "150.00", 200);
    }

    @Test
    public void happyPath() {
        simulateNewOrderRequest().symbol("AAPL").quantity("10").limitPrice("150.00").account("MAIN");
        verifyNewOrderRequest("quantity:10", "account:MAIN");
        verifyOrderPendingNewEvent("orderId:Parent#1");
    }
}
```

## Core Pattern

Each test is a sequence of **simulate** (inputs to the algo) and **verify** (outputs from the algo) calls.

- `simulate*` — inbound events: order requests from clients, exchange events, market data, time
- `verify*` — outbound messages: what the algo sends to exchanges or back to clients
- The framework's `@AfterEach` automatically asserts no unexpected messages remain

## simulate* Methods

### Client Order Requests
```java
simulateNewOrderRequest()                          // returns OrderRequestBuilder
simulateNewOrderRequest(String orderId)
simulateCancelRequest(String orderId)
simulateReplaceOrderRequest(String orderId, String originalOrderId)
simulateOrderStatusRequest(String orderId)
```

### Exchange/Venue Events (child order responses)
```java
simulateOrderNewEvent(String orderId)
simulateOrderPendingNewEvent(String orderId)
simulateTradeEvent(String orderId, String price, String quantity)
simulateOrderCancelEvent(String orderId)
simulateOrderCancelEvent(String orderId, String cancelRequestId)
simulateOrderCancelRejectEvent(String orderId)
simulateOrderReplaceEvent(String originalOrderId, String orderId)
simulateOrderReplaceRejectEvent(String originalOrderId, String orderId)
simulateOrderRejectEvent(String orderId)
simulateOrderRestateEvent(String orderId)
```

### Market Data
```java
simulateMarketFeedBBO(CharSequence symbol, String bidPrice, int bidSize, String askPrice, int askSize)
simulateMarketFeedTrade(CharSequence symbol, String price, String size)
simulateMarket(String symbol, String... orderBookEntries)       // L2 traditional format
simulateOrderBook(String symbol, long exchangeId, String... entries)  // L2 universal format
simulateUnknownMarketPrice(CharSequence symbol)
```

Order book entry format — separator line divides asks (above) from bids (below):
```
"7.001 @ 1440.99"   // ask
"0.001 @ 1440.98"   // ask
"---------------"   // bid/ask separator
"10.941 @ 1440.95"  // bid
"0.075 @ 1440.90"   // bid
```

### Time
```java
simulateTimeAdvance(Duration.ofSeconds(5))
setTime(long epochMillis)
```

## verify* Methods

All take `"fieldName:value"` pairs as varargs. Field names match the Deltix Trading API event fields.

```java
verifyNewOrderRequest(String... attrs)
verifyReplaceOrderRequest(String... attrs)
verifyCancelOrderRequest(String... attrs)
verifyOrderPendingNewEvent(String... attrs)
verifyOrderNewEvent(String... attrs)
verifyOrderPendingCancelEvent(String... attrs)
verifyOrderCancelEvent(String... attrs)
verifyOrderCancelRejectEvent(String... attrs)
verifyOrderTradeEvent(String... attrs)
verifyOrderReplaceEvent(String... attrs)
verifyOrderReplaceRejectEvent(String... attrs)
verifyOrderPendingReplaceEvent(String... attrs)
verifyOrderRejectEvent(String... attrs)
verifyOrderRestateEvent(String... attrs)
verifyOrderStatusEvent(String... attrs)
verifyPositionRequest(String... attrs)
verifyMessage(Class<?> messageClass, String... attrs)
verifyNoMessagesFromAlgorithm()
```

## OrderRequestBuilder

Fluent builder returned by `simulateNewOrderRequest()` and `simulateReplaceOrderRequest()`.

```java
simulateNewOrderRequest()
    .symbol("AAPL")
    .quantity("10")          // positive = BUY, negative = SELL (sets side automatically)
    .limitPrice("150.00")    // also sets OrderType.LIMIT
    .stopPrice("149.00")     // also sets OrderType.STOP
    .orderType(OrderType.MARKET)   // explicit type; overrides auto-inferred type
    .side(Side.SELL)               // explicit side; overrides sign-based auto-inference
    .displayQty("50")              // iceberg display quantity
    .trader("myTrader")            // set trader ID
    .account("MAIN")
    .exchange("NYSE")
    .timeInForce(TimeInForce.DAY)
    .custom("duration:00:00:05; skew:15")   // semicolon-separated name:value custom attributes
    .doSomethingNasty(req -> req.setFlags(1 << OrderFlags.ALL_OR_NONE.ordinal()))  // low-level access
```

### Auto-inference (setAutomateSomeAttrs)

By default the builder infers some fields automatically:
- `.limitPrice()` → also sets `OrderType.LIMIT`
- `.stopPrice()` → also sets `OrderType.STOP`
- `.quantity("10")` → sets `Side.BUY`; `.quantity("-10")` → sets `Side.SELL`

Disable with `setAutomateSomeAttrs(false)` in the constructor when the algo needs explicit control (e.g., testing MARKET orders, CUSTOM type, or when `.orderType()` must be set before price):

```java
public Test_MyAlgorithm() {
    super("BTCUSD", InstrumentType.FX);
    setAutomateSomeAttrs(false);   // must set .orderType() and .side() explicitly
}
```

### Custom Attributes

To use named custom attributes in `custom("name:value")`, override `getAttributeKeyByName`:

```java
@Override
protected int getAttributeKeyByName(String name) {
    if (name.equals("duration")) return MyAlgorithm.DURATION_ATTR_KEY;
    return super.getAttributeKeyByName(name);
}
```

## Instrument Definition

Call in `initAlgorithm()` or `@BeforeEach` (before tests run).

```java
// Basic — uses default precision
defineSyntheticInstrument(String symbol)
defineFutureInstrument(String symbol)
defineCurrencyInstrument(String symbol)

// With precision — tick size, decimal digits in order size, minimum order size
defineCurrencyInstrument("BTCUSD", 0.01, 3, "0.02")
defineFutureInstrument("EDZ7", 0.0025, 0, "1")

// Push to a specific algorithm instance (useful when passing the typed algorithm param)
defineCurrencyInstrument("EURUSD", algorithm)  // calls algorithm.onCurrencyUpdate()
defineFutureInstrument("EDZ7", algorithm)       // calls algorithm.onFutureUpdate()
```

Calling the no-algorithm overload routes through `algorithmMock`. Pass `algorithm` explicitly when you need to push instruments inside `initAlgorithm(A algorithm)` or a `@BeforeEach` that runs after construction:

```java
@Override
protected void initAlgorithm(MyAlgorithm algorithm) {
    super.initAlgorithm(algorithm);
    defineCurrencyInstrument("EURUSD", algorithm);   // algorithm is typed MyAlgorithm here
    defineCurrencyInstrument("EURUSD.LP1", algorithm);
}
```

## Auto-Generated IDs

The framework auto-increments order IDs:
- Parent orders: `Parent#1`, `Parent#2`, …
- Child orders: `Child#1`, `Child#2`, …
- Cancel requests: `CancelRequest#1`, …

Override `simulateNewOrderRequest()` in your test class to set defaults shared by all tests:
```java
@Override
protected OrderRequestBuilder simulateNewOrderRequest() {
    return super.simulateNewOrderRequest().symbol("AAPL");
}
```

## Common Test Patterns

### Reject Invalid Order
```java
simulateNewOrderRequest().quantity("10").account("INVALID");
verifyOrderRejectEvent("reason:Bad account");
```

### Child Order Submit + Fill
```java
simulateNewOrderRequest().quantity("10").limitPrice("100.00").account("GOOD");
verifyNewOrderRequest("quantity:10", "account:GOOD");
verifyOrderPendingNewEvent("orderId:Parent#1");

simulateTradeEvent("Child#1", "100.00", "10");
verifyOrderTradeEvent("orderId:Parent#1", "tradePrice:100.00", "tradeQuantity:10");
```

### Parent Cancel Propagation
```java
simulateCancelRequest("Parent#1");
verifyCancelOrderRequest("orderId:Child#1");
simulateOrderCancelEvent("Child#1");
verifyOrderCancelEvent("orderId:Parent#1");
```

### Timer / Timeout
```java
simulateNewOrderRequest().quantity("10").custom("duration:00:00:05");
verifyNewOrderRequest("orderId:Child#1");
verifyOrderPendingNewEvent("orderId:Parent#1");

simulateTimeAdvance(Duration.ofSeconds(5));
verifyCancelOrderRequest("orderId:Child#1");
verifyOrderCancelEvent("orderId:Parent#1", "reason:Order timeout");
```

### Market Data Guard
```java
simulateUnknownMarketPrice("AAPL");
simulateNewOrderRequest().quantity("10");
verifyOrderRejectEvent("reason:No market data");
```

### Verify N Events of the Same Type

```java
for (int i = 0; i < 3; i++)
    verifyOrderCancelEvent("reason:Order Expired");
```

## Advanced Setup

### Enable Verbose Logging

```java
public Test_MyAlgorithm() {
    LOGGER.setLevel(LogLevel.TRACE);   // LOGGER is protected static in base class
}
```

### Multi-Symbol Subscription

Override to let the algorithm subscribe to multiple instruments:

```java
@Override
protected MarketSubscription getAlgorithmMarketSubscription() {
    MarketSubscription subscription = super.getAlgorithmMarketSubscription();
    when(subscription.isSubscribedToAll()).thenReturn(true);
    when(subscription.isSubscribedToAllInstruments()).thenReturn(true);
    return subscription;
}
```

Or subscribe to a specific list:
```java
when(subscription.getSymbols()).thenReturn(Arrays.asList("BTCUSD", "LTCUSD"));
```

### Session Status Events

Algos that talk to LPs check session connectivity. Send a connected event directly to the algorithm:

```java
MutableSessionStatusEvent event = new MutableSessionStatusEvent();
event.setSourceId(AlphanumericCodec.encode("LP1"));
event.setStatus(SessionStatus.CONNECTED);
algorithm.onSessionStatusEvent(event);
```

Typically wrapped in a helper and called from `@BeforeEach`:
```java
@BeforeEach
public void initSessions() {
    simulateTradingSessionStatusConnected("LP1");
    simulateTradingSessionStatusConnected("LP2");
}

private void simulateTradingSessionStatusConnected(String lp) {
    MutableSessionStatusEvent event = new MutableSessionStatusEvent();
    event.setSourceId(AlphanumericCodec.encode(lp));
    event.setStatus(SessionStatus.CONNECTED);
    algorithm.onSessionStatusEvent(event);
}
```

### Capture Side-Channel Output

Algorithms that publish to output streams (auctions, RFQ, etc.) use `createOutputChannel()`. Override `getAlgorithmContext()` to capture those messages:

```java
private final List<AuctionMessage> auctionMessages = new ArrayList<>();

@Override
protected AlgorithmContext getAlgorithmContext() {
    AlgorithmContext mock = super.getAlgorithmContext();
    when(mock.createOutputChannel(anyString(), any(Class[].class))).thenReturn(new EmptyMessageChannel() {
        @Override
        public void send(Object msg) {
            if (msg instanceof AuctionMessage)
                auctionMessages.add((AuctionMessage) msg);
            else
                fail("Unexpected message type: " + msg.getClass().getSimpleName());
        }
    });
    return mock;
}
```

See `Test_AuctionAlgorithmMock` and `AbstractRFQAlgorithmTest` for complete examples.

## SingleLegExecutionAlgoUnitTest

Most algo tests extend `SingleLegExecutionAlgoUnitTest<A>` (which itself extends `BaseAbstractAlgorithmTest`). It wires up a single symbol and instrument type in the constructor, pre-sets `symbol` on all order requests, and adds convenience overloads:

```java
class Test_MyAlgo extends SingleLegExecutionAlgoUnitTest<MyAlgorithm> {

    public Test_MyAlgo() {
        super("BTCUSD", InstrumentType.FX);   // symbol and type shared across all tests
    }

    @Override
    protected MyAlgorithm createAlgorithm() {
        return new MyAlgorithm(getAlgorithmContext(), getCacheSettings());
    }
}
```

Extra methods available only in `SingleLegExecutionAlgoUnitTest`:

```java
// Market data — symbol is implicit
simulateMarketFeedBBO("149.99", 100, "150.00", 200)
simulateMarketFeedBBO(bidPrice, bidSize, askPrice, askSize)   // double overload
simulateMarketFeedTrade(price)                                 // double or @Decimal long

// Order book — symbol is implicit
simulateOrderBook(String... entries)
simulateOrderBook(long exchangeId, String... entries)

// Cancel with external ID
simulateCancelRequest("Parent#1", "EXCH-ORDER-999")
```

The protected `symbol` field holds the configured symbol string and can be used in test assertions.
