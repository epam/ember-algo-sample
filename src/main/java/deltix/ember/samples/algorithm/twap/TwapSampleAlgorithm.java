package deltix.ember.samples.algorithm.twap;

import deltix.anvil.util.Factory;
import deltix.anvil.util.annotation.Alphanumeric;
import deltix.anvil.util.codec.AlphanumericCodec;
import deltix.anvil.util.timer.TimerCallback;
import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.ember.message.trade.MutableOrderNewRequest;
import deltix.ember.message.trade.OrderNewRequest;
import deltix.ember.message.trade.OrderReplaceRequest;
import deltix.ember.message.trade.OrderType;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.ChildOrder;
import deltix.ember.service.algorithm.SimplifiedAbstractAlgorithm;
import deltix.ember.service.oms.cache.OrdersCacheSettings;
import deltix.gflog.LogLevel;

import static deltix.anvil.util.timer.TimerCallback.DO_NOT_RESCHEDULE;

/**
 * Time Weighted Average Price (TWAP) execution algo (Naive version)
 */
class TwapSampleAlgorithm extends SimplifiedAbstractAlgorithm<TwapOrder> {

    /** For simplicity we hard-code some security metadata parameters here. In real life you need to get them from Security Metadata Provider */
    private static final @Decimal long MIN_ORDER_SIZE = Decimal64Utils.parse("0.001");
    private static final @Decimal long ORDER_QUANTITY_PRECISION = Decimal64Utils.parse("0.000001");

    private final @Alphanumeric long defaultOrderDestination;

    TwapSampleAlgorithm(AlgorithmContext context, OrdersCacheSettings cacheSettings, String defaultOrderDestination) {
        super(context, cacheSettings);

        this.defaultOrderDestination = AlphanumericCodec.encode(defaultOrderDestination);
    }

    @Override
    protected Factory<TwapOrder> createParentOrderFactory() {
        return TwapOrder::new;
    }

    @Override
    protected Factory<ChildOrder<TwapOrder>> createChildOrderFactory() {
        return ChildOrder::new;
    }

    @Override
    protected void handleNewOrder(TwapOrder order, OrderNewRequest request) {
        order.copyExtraAttributes(request, false, getClock());
        if (LOGGER.isEnabled(LogLevel.INFO))
            LOGGER.info("TWAP order %s will send %s clips with interval %s msec").with(order.getOrderId()).with(order.getNumberOfClips()).with(order.getInterval());

        order.scheduleNextSliceTask(getTimer(), orderSliceTimerCallback);
    }


    @Override
    protected void handleReplace(TwapOrder order, OrderReplaceRequest request) {
        assert Decimal64Utils.isPositive(order.getRemainingQuantity()); // FullOrderValidator

        if (isLeader())
            sendReplaceRejectEvent(order, order.getLastOrder(), "CancelReplace is not supported by TWAP sample algo");
    }

    private final TimerCallback<TwapOrder> orderSliceTimerCallback = (nowMs, twapOrder) -> {
        assert twapOrder != null;
        if (!twapOrder.isCancelPending()) {
            nextClip(twapOrder);
            return twapOrder.getNextClipTime();
        }
        return DO_NOT_RESCHEDULE;
    };

    private void nextClip(TwapOrder twapOrder) {
        twapOrder.nextClip();
        if (isLeader())
            submitNextChildOrder(twapOrder);
    }

    private void submitNextChildOrder(TwapOrder order) {
        assert isLeader();
        @Decimal long clipQuantity = roundSize(order.getNextClipSize());

        if (Decimal64Utils.isGreater(clipQuantity, MIN_ORDER_SIZE)) {
            MutableOrderNewRequest childRequest = makeChildOrderRequest(order);
            childRequest.setQuantity(clipQuantity);
            childRequest.setOrderType(OrderType.LIMIT);
            if (!childRequest.hasDestinationId())
                childRequest.setDestinationId(defaultOrderDestination);
            submit(order, childRequest);
        }
    }


    private static long roundSize(final @Decimal long quantity) {
        return Decimal64Utils.max(Decimal64Utils.ZERO, Decimal64Utils.roundTowardsNegativeInfinity(quantity, ORDER_QUANTITY_PRECISION));
    }


}
