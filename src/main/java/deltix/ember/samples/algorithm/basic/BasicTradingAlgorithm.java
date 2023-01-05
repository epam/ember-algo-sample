package deltix.ember.samples.algorithm.basic;

import deltix.anvil.util.codec.AlphanumericCodec;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.ember.message.trade.*;
import deltix.ember.service.algorithm.AlgoOrder;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.ChildOrder;
import deltix.ember.service.algorithm.SimpleAlgorithm;
import deltix.ember.service.data.Order;
import deltix.ember.service.oms.cache.OrdersCacheSettings;

/**
 * Basic "BUY side" example that illustrates how to submit, replace, and cancel an order
 */
public class BasicTradingAlgorithm extends SimpleAlgorithm {

    BasicTradingAlgorithm(AlgorithmContext context) {
        super(context);
    }

    BasicTradingAlgorithm(AlgorithmContext context, OrdersCacheSettings cacheSettings) {
        super(context, cacheSettings);
    }

    /**
     * This method only illustrates main trading requests
     */
    void sendOrder() {
        // Submit new order
        MutableOrderNewRequest newOrder = makeMarketOrder(Side.BUY, Decimal64Utils.fromLong((long) 1000), "NFLX");
        newOrder.setDestinationId(AlphanumericCodec.encode("SIM"));
        newOrder.setExchangeId(AlphanumericCodec.encode("ACK"));
        Order order = submit(newOrder);
        LOGGER.info("Submitted order %s").with(order);
    }

    @Override
    protected void handleOrderNewEvent(ChildOrder<AlgoOrder> order, OrderNewEvent event) {
        // Cancel-Replace order (increase quantity from 1000 to 1500)
        MutableOrderReplaceRequest replace = makeReplaceRequest(order);
        replace.setQuantity(Decimal64Utils.fromLong((long) 1500));
        replace(order, replace);
    }

    @Override
    protected void handleOrderReplaceEvent(ChildOrder<AlgoOrder> order, OrderReplaceEvent event) {
        // Cancel working order
        cancel(order, "Cancel reason goes here");
    }

    @Override
    protected void handleOrderRejectEvent(ChildOrder<AlgoOrder> order, OrderRejectEvent event) {
        LOGGER.info("Order %s is rejected. Reason: %s").with(order.getOrderId()).with(event.getReason());
    }

    @Override
    protected void handleOrderCancelEvent(ChildOrder<AlgoOrder> order, OrderCancelEvent event) {
        LOGGER.info("Order %s is cancelled. Reason: %s").with(order.getOrderId()).with(event.getReason());
    }

    @Override
    protected void handleTradeEvent(ChildOrder<AlgoOrder> order, OrderTradeReportEvent event) {
        LOGGER.info("Order %s is filled. %s @ %s").with(order.getOrderId()).withDecimal64(event.getTradeQuantity()).with(event.getTradePrice());
    }
}
