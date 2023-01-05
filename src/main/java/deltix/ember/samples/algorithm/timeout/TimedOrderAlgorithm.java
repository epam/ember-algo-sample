package deltix.ember.samples.algorithm.timeout;

import deltix.anvil.util.TypeConstants;
import deltix.anvil.util.timer.OneTimerCallback;
import deltix.ember.message.trade.OrderNewRequest;
import deltix.ember.service.algorithm.AlgoOrder;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.SimpleAlgorithm;
import deltix.ember.service.oms.cache.OrdersCacheSettings;
import deltix.ember.service.valid.InvalidOrderException;

/**
 * Execution algorithm that implements order execution timeout
 * This sample uses {@link OrderNewRequest#getExpireTime()}
 */
public class TimedOrderAlgorithm extends SimpleAlgorithm {
    private final OneTimerCallback<Object> orderTimeoutCallback = this::onOrderTimeout;

    TimedOrderAlgorithm(AlgorithmContext context, OrdersCacheSettings cacheSettings) {
        super(context, cacheSettings);
    }

    @Override
    protected void handleNewOrder(AlgoOrder parentOrder, OrderNewRequest request) {
        if (isLeader()) {
            if (request.getExpireTime() == TypeConstants.TIMESTAMP_NULL)
                throw new InvalidOrderException("Order must specify expiration time");
            if (request.getExpireTime() <= currentTime())
                throw new InvalidOrderException("Order expiration time must be in the future");

            sendPendingNewEvent(parentOrder);

            sendChildOrder(parentOrder);

            //System.out.println("Scheduling order timeout " + (request.getExpireTime() - currentTime()) + " msec from now");
            getTimer().schedule(request.getExpireTime(), orderTimeoutCallback, parentOrder);
        } else {
            LOGGER.info("Ignoring order %s (not in LEADER role)").with(request.getOrderId());
        }
    }

    /**
     * passed as callback to order expiration timer job
     */
    private void onOrderTimeout(long nowMs, Object orderCookie) {
        AlgoOrder parentOrder = (AlgoOrder) orderCookie;
        if (parentOrder.isActive() && isLeader())
            cancelAlgoOrder(parentOrder, "Timeout");
    }

    private void sendChildOrder(AlgoOrder parentOrder) {
        submit(parentOrder, makeChildOrderRequest(parentOrder));
    }

}
