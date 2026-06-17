package deltix.ember.samples.algorithm.marketmaker;

import com.epam.deltix.dfp.Decimal;
import deltix.anvil.message.NodeStatusEvent;
import deltix.anvil.util.Factory;
import deltix.anvil.util.annotation.Alphanumeric;
import deltix.anvil.util.annotation.Timestamp;
import deltix.ember.message.trade.*;
import deltix.ember.message.trade.oms.MutablePositionRequest;
import deltix.ember.message.trade.oms.PositionReport;
import deltix.ember.service.EmberConstants;
import deltix.ember.service.PositionRequestHandler;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.MarketSubscription;
import deltix.ember.service.algorithm.md.InstrumentDataFactory;
import deltix.ember.service.algorithm.v2.AbstractL2TradingAlgorithm;
import deltix.ember.service.algorithm.v2.OutboundOrderProcessorImpl;
import deltix.ember.service.algorithm.v2.order.OutboundOrder;
import deltix.ember.service.oms.cache.OrdersCacheSettings;

public class MarketMakerAlgorithm extends AbstractL2TradingAlgorithm<MarketMakerHandler, OutboundOrder> {
    private final MarketMakerSettings settings;
    private State state;

    public MarketMakerAlgorithm(AlgorithmContext context, OrdersCacheSettings cacheSettings, MarketMakerSettings settings) {
        super(context, cacheSettings);
        this.settings = settings;
    }

    /// region factory methods

    /** Factory that creates per-instrument state objects, MarketMakerHandler in our case */
    @Override
    protected InstrumentDataFactory<MarketMakerHandler> createInstrumentDataFactory() {
        return (symbol, instrumentType) -> new MarketMakerHandler(symbol, instrumentType, this, context.getLogger());
    }

    /** Factory for our order event processor */
    @Override
    protected OutboundOrderProcessorImpl<OutboundOrder> createOutboundOrderProcessor(AlgorithmContext context, OrdersCacheSettings cacheSettings) {
        return new MarketMakerOrderProcessor(context, cacheSettings);
    }

    /** boilerplate code */
    @Override
    protected Factory<OutboundOrder> createOrderFactory() {
        return OutboundOrder::new;
    }

    /// endregion

    /** System restart handler */
    @Override
    public void onNodeStatusEvent(NodeStatusEvent event) {
        super.onNodeStatusEvent(event);
        if (isLeader()) {
            orderProcessor.iterateActiveOrders((order, cookie) -> { cancelOrder(order, "Reboot"); return false; }, null);

            if (!hasActiveOrders()) {
                submitPositionRequest();
            }
        }
    }

    public void submitPositionRequest() {
        MutablePositionRequest request = new MutablePositionRequest();
        request.setRequestId(context.getRequestSequence().next());
        request.setSourceId(getId());
        request.setDestinationId(EmberConstants.EMBER_SOURCE_ID);
        request.setProjection("Source/Symbol");
        request.setSrc(getId());

        ((PositionRequestHandler)getOMS()).onPositionRequest(request);

        state = State.WAITING_FOR_POSITION_RESPONSE;
    }

    @Override
    public void onPositionReport(PositionReport response) {
        if (response.getError() != null) {
            LOGGER.error("Market Maker initialization failed to obtain Position of Source/Symbol projection. Make sure Risk Projection was configured");
            return;
        }

        if (!response.isFound()) {
            LOGGER.warn("Source/Symbol projection was not found");
            state = State.READY;
            return;
        }

        MarketMakerHandler handler = get(response.getSymbol());
        if (handler != null) {
            handler.updatePosition(response);
        } else {
            LOGGER.warn("Canceling unexpected position report %s for symbol %s").with(response).with(response.getSymbol());
        }

        if (response.isLast()) {
            state = State.READY;
        }
    }

    /** Submits OrderNewRequest as quoting order */
    OutboundOrder submitQuotingOrder(CharSequence symbol, @Decimal long orderSize, @Decimal long limitPrice, @Alphanumeric long exchangeId, Side side) {
        MutableOrderNewRequest result = orderProcessor.makeSubmitRequest();
        result.setSide(side);
        result.setSymbol(symbol);
        result.setQuantity(orderSize);
        result.setLimitPrice(limitPrice);
        result.setOrderType(OrderType.LIMIT);
        result.setDestinationId(exchangeId);
        result.setTimeInForce(TimeInForce.GOOD_TILL_CANCEL);
        result.setUserData("Quoter");
        return submit(result);
    }

    /** Submits OrderNewRequest as hedging order */
    OutboundOrder submitHedgingOrder(CharSequence symbol, @Decimal long orderSize, @Decimal long limitPrice, @Alphanumeric long exchangeId, Side side) {
        MutableOrderNewRequest result = orderProcessor.makeSubmitRequest();
        result.setSide(side);
        result.setSymbol(symbol);
        result.setQuantity(orderSize);
        result.setLimitPrice(limitPrice);
        result.setOrderType(OrderType.LIMIT);
        result.setDestinationId(exchangeId);
        result.setTimeInForce(TimeInForce.IMMEDIATE_OR_CANCEL);
        result.setUserData("Hedger");
        return submit(result);
    }

    void cancelOrder(OutboundOrder order, CharSequence reason) {
        MutableOrderCancelRequest result = orderProcessor.makeCancelRequest(order, reason);
        cancel(order, result);
    }

    /** This order event processor is basically dispatching order events to per-instrument state handler (MarketMakerHandler) */
    private class MarketMakerOrderProcessor extends OutboundOrderProcessorImpl<OutboundOrder> {

        public MarketMakerOrderProcessor(AlgorithmContext context, OrdersCacheSettings cacheSettings) {
            super(context, cacheSettings, createOrderFactory(), createOrderEntryReqFactory(), MarketMakerAlgorithm.this.tradingMessages);
        }

        @Override
        public void onTradeReport(OutboundOrder order, OrderTradeReportEvent event) {
            super.onTradeReport(order, event);

            if (isReady()) {
                MarketMakerHandler handler = get(order.getSymbol());
                if (handler != null) {
                    handler.onFilled(order, event);
                } else {
                    LOGGER.warn("Fill %s for unknown symbol %s").with(order).with(order.getSymbol());
                }
            } else {
                updateState();
            }
        }

        @Override
        public void onOrderReject(OutboundOrder order, OrderRejectEvent event) {
            super.onOrderReject(order, event);

            if (isReady()) {
                MarketMakerHandler handler = get(order.getSymbol());
                if (handler != null) {
                    handler.onRejected(order, event);
                } else {
                    LOGGER.warn("Reject %s for unknown symbol %s").with(order).with(order.getSymbol());
                }
            } else {
                updateState();
            }
        }

        @Override
        public void onOrderCancel(OutboundOrder order, OrderCancelEvent event) {
            super.onOrderCancel(order, event);

            if (isReady()) {
                MarketMakerHandler handler = get(order.getSymbol());
                if (handler != null) {
                    handler.onCanceled(order, event);
                } else {
                    LOGGER.warn("Cancel %s for unknown symbol %s").with(order).with(order.getSymbol());
                }
            } else {
                updateState();
            }
        }

        @Override
        public void onOrderNew(OutboundOrder order, OrderNewEvent event) {
            super.onOrderNew(order, event);

            if (isReady()) {
                MarketMakerHandler handler = get(order.getSymbol());
                if (handler != null) {
                    handler.onNew(order, event);
                } else {
                    LOGGER.warn("Submit %s for unknown symbol %s").with(order).with(order.getSymbol());
                }
            } else {
                updateState();
            }
        }
    }

    /// Helpers

    MarketMakerSettings getSettings() {
        return settings;
    }

    @Timestamp
    long getTime() {
        return super.currentTime();
    }

    /** @return true if algorithm restored position
     * otherwise iterates active orders
     * and can change state */
    boolean isReady() {
        return state == State.READY;
    }

    void updateState() {
        if (state == State.WAITING_FOR_POSITION_RESPONSE)
            return;

        if (!hasActiveOrders())
            submitPositionRequest();
    }

    boolean hasActiveOrders() {
        boolean[] result = {false};
        orderProcessor.iterateActiveOrders((order, cookie) -> { result[0] = true; return true; }, null);
        return result[0];
    }

    boolean isSubscribed(String symbol) {
        MarketSubscription subscription = context.getMarketSubscription();
        return (subscription != null && (subscription.isSubscribedToAll() || subscription.getSymbols().contains(symbol)));
    }

    private enum State {
        READY,
        WAITING_FOR_POSITION_RESPONSE
    }
}
