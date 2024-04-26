package deltix.ember.samples.algorithm.marketmaker;

import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.anvil.message.NodeStatusEvent;
import deltix.anvil.util.Factory;
import deltix.anvil.util.annotation.Alphanumeric;
import deltix.anvil.util.annotation.Timestamp;
import deltix.ember.message.trade.*;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.MarketSubscription;
import deltix.ember.service.algorithm.md.InstrumentDataFactory;
import deltix.ember.service.algorithm.v2.AbstractL2TradingAlgorithm;
import deltix.ember.service.algorithm.v2.OutboundOrderProcessorImpl;
import deltix.ember.service.algorithm.v2.order.OutboundOrder;
import deltix.ember.service.oms.cache.OrdersCacheSettings;

public class MarketMakerAlgorithm extends AbstractL2TradingAlgorithm<MarketMakerHandler, OutboundOrder> {
    private final MarketMakerSettings settings;

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
            orderProcessor.iterateActiveOrders(this::onLeaderState, null);
        }
    }

    /** Called from restart handler to classify (or reference) remaining active orders (helps state recovery on restart) */
    private boolean onLeaderState(OutboundOrder order, Void cookie) {
        if (order.isActive()) {
            MarketMakerHandler handler = get(order.getSymbol());
            if (handler != null) {
                handler.onLeaderState(order);
            } else {
                LOGGER.warn("Canceling unexpected active order %s for symbol %s").with(order).with(order.getSymbol());
                orderProcessor.cancelOrder(order, orderProcessor.makeCancelRequest(order, "Reboot"));
            }
        }
        return false;
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

    /** Submits OrderReplaceRequest to adjust price or size of quoting order */
    void replaceQuotingOrder(OutboundOrder order, @Decimal long newPrice, @Decimal long newSize) {
        MutableOrderReplaceRequest result = orderProcessor.makeReplaceRequest(order);
        result.setLimitPrice(newPrice);
        result.setQuantity(newSize);
        replace(order, result);
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

    void cancelOrder(OutboundOrder order) {
        MutableOrderCancelRequest result = orderProcessor.makeCancelRequest(order, null);
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

            if (isLeader()) {
                MarketMakerHandler handler = get(order.getSymbol());
                if (handler != null) {
                    handler.onFilled(order, event);
                } else {
                    LOGGER.warn("Fill %s for unknown symbol %s").with(order).with(order.getSymbol());
                }
            }
        }

        @Override
        public void onOrderReject(OutboundOrder order, OrderRejectEvent event) {
            super.onOrderReject(order, event);

            if (isLeader()) {
                MarketMakerHandler handler = get(order.getSymbol());
                if (handler != null) {
                    handler.onRejected(order, event);
                } else {
                    LOGGER.warn("Reject %s for unknown symbol %s").with(order).with(order.getSymbol());
                }
            }
        }

        @Override
        public void onOrderCancel(OutboundOrder order, OrderCancelEvent event) {
            super.onOrderCancel(order, event);

            if (isLeader()) {
                MarketMakerHandler handler = get(order.getSymbol());
                if (handler != null) {
                    handler.onCanceled(order, event);
                } else {
                    LOGGER.warn("Cancel %s for unknown symbol %s").with(order).with(order.getSymbol());
                }
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

    /** @return true if algorithm is running live (as cluster leader or standalone) */
    boolean isLeaderNode() {
        return isLeader();
    }

    boolean isSubscribed(String symbol) {
        MarketSubscription subscription = context.getMarketSubscription();
        return (subscription != null && (subscription.isSubscribedToAll() || subscription.getSymbols().contains(symbol)));
    }
}
