package deltix.ember.samples.algorithm.sim;

import deltix.anvil.util.AsciiStringBuilder;
import deltix.anvil.util.CharSequenceUtil;
import deltix.anvil.util.codec.AlphanumericCodec;
import com.epam.deltix.dfp.Decimal;
import deltix.ember.message.trade.OrderNewRequest;
import deltix.ember.service.algorithm.AlgoOrder;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.SimpleAlgorithm;
import deltix.ember.service.oms.cache.OrdersCacheSettings;

/**
 * Algorithm that simulates exchange
 */
public class ExchangeSimulationAlgorithm extends SimpleAlgorithm {

    private final AsciiStringBuilder exchangeBuffer = new AsciiStringBuilder(16);

    ExchangeSimulationAlgorithm(AlgorithmContext container, OrdersCacheSettings cacheSettings) {
        super(container, cacheSettings);
    }

    @Override
    protected void handleNewOrder(AlgoOrder order, OrderNewRequest request) {
        if (isLeader()) {
            CharSequence exchange = AlphanumericCodec.decode(request.getExchangeId(), exchangeBuffer);
            if (CharSequenceUtil.equals(exchange, "REJECT")) {
                sendRejectEvent(order, "This exchange always rejects orders");
            } else if (CharSequenceUtil.equals(exchange, "CANCEL")) {
                sendNewEvent(order);
                sendCancelEvent(order, "This exchange always cancels orders", null);
            } else if (CharSequenceUtil.equals(exchange, "FILL")) {
                sendNewEvent(order);
                sendTradeEvent(order, order.getWorkingQuantity(), getSimulatedFillPrice(request));
            } else {
                sendRejectEvent(order, "Unsupported exchange");
            }
        } else {
            LOGGER.info("Ignoring order %s (not in LEADER role)").with(request.getOrderId());
        }
    }

    @Decimal
    private long getSimulatedFillPrice(OrderNewRequest request) {
        @Decimal long limitPrice = request.getLimitPrice();
        //if (TypeConstants.DECIMAL64_NULL == limitPrice) {
        //    return getLastKnownPrice(request.getSymbol());
        //}
        return limitPrice;
    }
}
