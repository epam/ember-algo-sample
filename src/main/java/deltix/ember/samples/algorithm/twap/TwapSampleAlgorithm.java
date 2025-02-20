package deltix.ember.samples.algorithm.twap;

import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import com.epam.deltix.gflog.api.LogLevel;
import deltix.anvil.util.Factory;
import deltix.anvil.util.annotation.Alphanumeric;
import deltix.anvil.util.codec.AlphanumericCodec;
import deltix.anvil.util.timer.TimerCallback;
import deltix.ember.message.trade.*;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.md.InstrumentDataFactory;
import deltix.ember.service.algorithm.md.SimpleInstrumentPrices;
import deltix.ember.service.algorithm.v2.AbstractExecutionAlgorithmEx;
import deltix.ember.service.algorithm.v2.order.OutboundChildOrder;
import deltix.ember.service.data.OrderState;
import deltix.ember.service.oms.cache.OrdersCacheSettings;

import static deltix.anvil.util.timer.TimerCallback.DO_NOT_RESCHEDULE;

/**
 * Time Weighted Average Price (TWAP) execution algo (Naive version)
 */
class TwapSampleAlgorithm extends AbstractExecutionAlgorithmEx<SimpleInstrumentPrices, TwapOrder, OutboundChildOrder> {

    /** For simplicity, we hard-code some security metadata parameters here. In real life you need to get them from Security Metadata Provider */
    private static final @Decimal long MIN_ORDER_SIZE = Decimal64Utils.parse("0.001");
    private static final @Decimal long ORDER_QUANTITY_PRECISION = Decimal64Utils.parse("0.000001");

    private final @Alphanumeric long defaultOrderDestination;

    TwapSampleAlgorithm(AlgorithmContext context, OrdersCacheSettings cacheSettings, String defaultOrderDestination) {
        super(context, cacheSettings);

        this.defaultOrderDestination = AlphanumericCodec.encode(defaultOrderDestination);
    }

    @Override
    protected Factory<TwapOrder> createInboundOrderFactory() {
        return TwapOrder::new;
    }

    @Override
    protected Factory<OutboundChildOrder> createOutboundOrderFactory() {
        return OutboundChildOrder::new;
    }

    @Override
    protected InstrumentDataFactory<SimpleInstrumentPrices> createInstrumentDataFactory() {
        return SimpleInstrumentPrices::new;
    }

    @Override
    protected InboundOrderProcessorExImpl createInboundOrderProcessor(AlgorithmContext context, OrdersCacheSettings cacheSettings) {
        return new TwapInboundOrderProcessor(context, cacheSettings);
    }

    @Override
    protected OutboundOrderProcessorExImpl createOutboundOrderProcessor(AlgorithmContext context, OrdersCacheSettings cacheSettings) {
        return new TwapOutboundOrderProcessor(context, cacheSettings);
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
            MutableOrderNewRequest childRequest = makeSubmitRequest(order);
            childRequest.setQuantity(clipQuantity);
            childRequest.setOrderType(OrderType.LIMIT);
            if (!childRequest.hasDestinationId())
                childRequest.setDestinationId(defaultOrderDestination);

            submit(childRequest);
        }
    }

    private static long roundSize(final @Decimal long quantity) {
        return Decimal64Utils.max(Decimal64Utils.ZERO, Decimal64Utils.roundTowardsNegativeInfinity(quantity, ORDER_QUANTITY_PRECISION));
    }

    protected class TwapInboundOrderProcessor extends InboundOrderProcessorExImpl {

        public TwapInboundOrderProcessor(AlgorithmContext context, OrdersCacheSettings cacheSettings) {
            super(context, cacheSettings);
        }

        @Override
        protected void handleNewOrderRequest(OrderNewRequest request, TwapOrder order) {
            order.copyExtraAttributes(request, getClock());
            if (LOGGER.isEnabled(LogLevel.INFO))
                LOGGER.info("TWAP order %s will send %s clips with interval %s msec").with(order.getWorkingOrder().getOrderId()).with(order.getNumberOfClips()).with(order.getInterval());

            order.scheduleNextSliceTask(getTimer(), orderSliceTimerCallback);
        }

        @Override
        protected void handleCancelOrderRequest(OrderCancelRequest request, TwapOrder order) {
            if (isLeader()) {
                if (!order.isFirstActiveCancelRequest(request.getRequestId()))
                    sendCancelRejectEvent(order, "Already pending cancel", request.getRequestId());

                cancelParentOrder(order, request.getReason(), request.getRequestId());
            }
        }
    }

    protected class TwapOutboundOrderProcessor extends OutboundOrderProcessorExImpl {

        public TwapOutboundOrderProcessor(AlgorithmContext context, OrdersCacheSettings cacheSettings) {
            super(context, cacheSettings);
        }

        @Override
        protected void handleOrderPendingNewEvent(OutboundChildOrder order, OrderPendingNewEvent event) {
            if (isLeader()) {
                if (order.getParent() != null) {
                    TwapOrder parent = (TwapOrder) order.getParent();
                    switch (parent.getState()) {
                        case UNACKNOWLEDGED:
                            inboundOrderProcessor.sendPendingNewEvent(parent);
                            break;
                        case ACKNOWLEDGED: /*do nothing*/
                            break;
                        default:
                            if (parent.isFinal())
                                LOGGER.warn("Order %s: Not expecting %s event in %s state").with(parent).with(event.getClass().getSimpleName()).with(parent.getState());
                    }
                }
            }
        }

        @Override
        protected void handleOrderNewEvent(OutboundChildOrder order, OrderNewEvent event) {
            if (isLeader()) {
                if (order.getParent() != null) {
                    TwapOrder parent = (TwapOrder) order.getParent();
                    switch (parent.getState()) {
                        case UNACKNOWLEDGED:
                        case ACKNOWLEDGED:
                            inboundOrderProcessor.sendNewEvent(parent);
                            break;
                        case OPEN: /*do nothing*/
                        case OPEN_PARTIALLY_FILLED:
                            break;
                        default:
                            if (parent.isFinal())
                                LOGGER.warn("Order %s: Not expecting %s event in %s state").with(parent).with(event.getClass().getSimpleName()).with(parent.getState());
                    }
                }
            }
        }

        @Override
        protected void handleOrderCancelEvent(OutboundChildOrder order, OrderCancelEvent event) {
            if (isLeader()) {
                if (order.getParent() != null) {
                    TwapOrder parent = (TwapOrder) order.getParent();

                    switch (parent.getState()) {
                        case UNACKNOWLEDGED:
                        case ACKNOWLEDGED:
                        case OPEN:
                        case OPEN_PARTIALLY_FILLED:
                            if (!parent.hasActiveChildren()) {
                                inboundOrderProcessor.sendCancelEvent(parent, event.getReason(), null);
                            } else if (!parent.isCancelPending()) {
                                cancelParentOrder(parent, event.getReason(), null);
                            }
                            break;
                        default:
                            LOGGER.warn("Order %s: Not expecting %s event in %s state").with(parent).with(event.getClass().getSimpleName()).with(parent.getState());
                    }
                }
            }
        }

        @Override
        protected void handleOrderRejectEvent(OutboundChildOrder order, OrderRejectEvent event) {
            if (isLeader()) {
                if (order.getParent() != null) {
                    assert order.isFinal();
                    TwapOrder parent = (TwapOrder) order.getParent();

                    switch (parent.getState()) {
                        case UNACKNOWLEDGED:
                        case ACKNOWLEDGED:
                        case OPEN:
                            if (parent.isCancelPending()) {
                                if (!parent.hasActiveChildren()) {
                                    inboundOrderProcessor.sendCancelEvent(parent, event.getReason(), null);
                                }
                            } else {
                                if (parent.hasActiveChildren() || parent.getState() != OrderState.UNACKNOWLEDGED) {
                                    cancelParentOrder(parent, event.getReason(), null);
                                } else {
                                    inboundOrderProcessor.sendRejectEvent(parent, "Received reject for child order");
                                }
                            }
                            break;
                        case OPEN_PARTIALLY_FILLED: // since we cannot issue Reject for already partially filled order let's generate a cancel
                            if (!parent.hasActiveChildren()) {
                                inboundOrderProcessor.sendCancelEvent(parent, event.getReason(), null);
                            } else if (!parent.isCancelPending()) {
                                cancelParentOrder(parent, event.getReason(), null);
                            }
                            break;
                        default:
                            LOGGER.warn("Order %s: Not expecting %s event in %s state").with(parent).with(event.getClass().getSimpleName()).with(parent.getState());
                    }
                }
            }
        }

        @Override
        protected void handleTradeEvent(OutboundChildOrder order, OrderTradeReportEvent event) {
            if (isLeader()) {
                if (order.getParent() != null) {
                    TwapOrder parent = (TwapOrder) order.getParent();
                    switch (parent.getState()) {
                        case UNACKNOWLEDGED:
                        case ACKNOWLEDGED:
                        case OPEN:
                        case OPEN_PARTIALLY_FILLED:
                            inboundOrderProcessor.sendTradeEvent(parent, inboundOrderProcessor.makeTradeReportEvent(parent, event));
                            break;
                        default:
                            LOGGER.warn("Order %s: Not expecting %s event in %s state").with(parent).with(event.getClass().getSimpleName()).with(parent.getState());
                    }
                }
            }
        }
    }
}
