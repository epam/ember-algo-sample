package deltix.ember.samples.algorithm.arbitrage;

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
import deltix.ember.service.algorithm.util.OrderBookHelper;
import deltix.ember.service.algorithm.v2.AbstractL2TradingAlgorithm;
import deltix.ember.service.algorithm.v2.OutboundOrderProcessorImpl;
import deltix.ember.service.algorithm.v2.order.OutboundOrder;
import deltix.ember.service.oms.cache.OrdersCacheSettings;
import deltix.orderbook.core.api.OrderBook;
import deltix.orderbook.core.options.OrderBookType;

/**
 * Algorithm subscribes to price streams from two exchanges (for example, BINANCE and OKEX).
 * Want to place a LIMIT BUY order on OKEX if BINANCE last traded price is higher than OKEX ask and BINANCE last trade timestamp is newer than OKEX ask timestamp.
 * If the order is filled, want to place passive SELL order on BINANCE to hedge (and want to replace at the top ask until order is filled).
 * To avoid hitting per-exchange request rate limits we replace only if price has changed, and if a replace request was sent within last 100 ms don't send another replace.
 */
public class ArbitrageAlgorithm extends AbstractL2TradingAlgorithm<ArbitrageHandler, OutboundOrder> {
    private final ArbitrageSettings settings;

    public ArbitrageAlgorithm(AlgorithmContext context, OrdersCacheSettings cacheSettings, ArbitrageSettings settings) {
        super(context, cacheSettings);
        this.settings = settings;
    }

    /// region factory methods

    /** Factory that creates per-instrument state objects, ArbitrageHandler in our case */
    @Override
    protected InstrumentDataFactory<ArbitrageHandler> createInstrumentDataFactory() {
        return (symbol, instrumentType) -> new ArbitrageHandler(symbol, instrumentType, this, context.getLogger());
    }

    /** Factory for our order event processor */
    @Override
    protected OutboundOrderProcessorImpl<OutboundOrder> createOutboundOrderProcessor(AlgorithmContext context, OrdersCacheSettings cacheSettings) {
        return new ArbitrageOrderProcessor(context, cacheSettings);
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

    /** Called from restart handler to classify given remaining active order as Entry or Exit (helps state recovery on restart) */
    private boolean onLeaderState(OutboundOrder order, Void cookie) {
        if (order.isActive()) {
            ArbitrageHandler handler = get(order.getSymbol());
            if (handler != null && handler.isMonitoring()) {
                handler.onLeaderState(order);
            } else {
                LOGGER.warn("Canceling unexpected active order %s for symbol %s").with(order).with(order.getSymbol());
                orderProcessor.cancelOrder(order, orderProcessor.makeCancelRequest(order, "Reboot"));
            }
        }
        return false;
    }


    /** Submits OrderNewRequest for ENTRY order */
    OutboundOrder submitEntryOrder(CharSequence symbol, @Decimal long orderSize, @Decimal long limitPrice, @Alphanumeric long exchangeId) {
        MutableOrderNewRequest result = orderProcessor.makeSubmitRequest();
        result.setSide(Decimal64Utils.isPositive(orderSize) ? Side.BUY : Side.SELL);
        result.setSymbol(symbol);
        result.setQuantity(Decimal64Utils.abs(orderSize));
        result.setLimitPrice(limitPrice);
        result.setOrderType(OrderType.LIMIT);
        result.setDestinationId(exchangeId);
        result.setTimeInForce(TimeInForce.IMMEDIATE_OR_CANCEL);
        result.setUserData("Entry");
        return submit(result);
    }

    /** Submits OrderNewRequest for EXIT order */
    OutboundOrder submitExitOrder(OutboundOrder entryOrder, @Decimal long limitPrice, @Alphanumeric long exchangeId) {
        MutableOrderNewRequest result = orderProcessor.makeSubmitRequest();
        result.setSide((entryOrder.getSide() == Side.BUY) ? Side.SELL: Side.BUY);
        result.setSymbol(entryOrder.getSymbol());
        result.setQuantity(entryOrder.getTotalExecutedQuantity());
        result.setLimitPrice(limitPrice);
        result.setOrderType(OrderType.LIMIT);
        result.setDestinationId(exchangeId);
        result.setTimeInForce(TimeInForce.GOOD_TILL_CANCEL);
        result.setUserData("Exit");
        return submit(result);
    }

    /** Submits OrderReplaceRequest to adjust price of EXIT order */
    void replaceExitOrder(OutboundOrder exitOrder, @Decimal long newPrice) {
        MutableOrderReplaceRequest result = orderProcessor.makeReplaceRequest(exitOrder);
        result.setLimitPrice(newPrice);
        replace(exitOrder, result);
    }

    /** This order event processor is basically dispatching order events to per-instrument state handler (ArbitrageHandler) */
    private class ArbitrageOrderProcessor extends OutboundOrderProcessorImpl<OutboundOrder> {

        public ArbitrageOrderProcessor(AlgorithmContext context, OrdersCacheSettings cacheSettings) {
            super(context, cacheSettings, createOrderFactory(), createOrderEntryReqFactory(), ArbitrageAlgorithm.this.tradingMessages);
        }

        @Override
        public void onTradeReport(OutboundOrder order, OrderTradeReportEvent event) {
            super.onTradeReport(order, event);

            if (isLeader()) {
                ArbitrageHandler handler = get(order.getSymbol());
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
                ArbitrageHandler handler = get(order.getSymbol());
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
                ArbitrageHandler handler = get(order.getSymbol());
                if (handler != null) {
                    handler.onCanceled(order, event);
                } else {
                    LOGGER.warn("Cancel %s for unknown symbol %s").with(order).with(order.getSymbol());
                }
            }
        }
    }

    /// Helpers

    ArbitrageSettings getSettings() {
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
