package deltix.ember.samples.algorithm.autocert;

import deltix.anvil.util.AsciiStringBuilder;
import deltix.anvil.util.CharSequenceUtil;
import deltix.anvil.util.Factory;
import deltix.anvil.util.timer.TimerCallback;
import deltix.anvil.util.timer.TimerJob;
import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.ember.message.trade.OrderCancelRequest;
import deltix.ember.message.trade.OrderEvent;
import deltix.ember.message.trade.OrderNewRequest;
import deltix.ember.message.trade.OrderReplaceRequest;
import deltix.ember.service.algorithm.AbstractAlgorithm;
import deltix.ember.service.algorithm.AlgoOrder;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.ChildOrder;
import deltix.ember.service.algorithm.md.InstrumentDataFactory;
import deltix.ember.service.algorithm.md.SimpleInstrumentPrices;
import deltix.ember.service.algorithm.util.OrderAttributesParser;
import deltix.ember.service.oms.cache.OrdersCacheSettings;

import java.util.function.Function;

class AutoCertOrder extends AlgoOrder {
    AsciiStringBuilder scenario = new AsciiStringBuilder();
    int nextScenarioEventIndex;
    boolean rejectCancelRequests;
    public TimerJob delayTimerJob;

    void setScenario(CharSequence scenario) {
        nextScenarioEventIndex = 0;

        if (CharSequenceUtil.isEmptyOrNull(scenario))
            scenario = "REJECT:Missing scenario tag";

        this.scenario.clear();
        if (scenario != null)
            this.scenario.append(scenario);
    }

    @Override
    public void onDeactivate(OrderEvent finalEvent) {
        super.onDeactivate(finalEvent);
        if (delayTimerJob != null) {
            delayTimerJob.cancel();
            delayTimerJob = null;
        }
    }

    @Override
    public void clear() {
        super.clear();
        rejectCancelRequests = false;
        nextScenarioEventIndex = 0;
        scenario.clear();
        delayTimerJob = null;
    }

}

/**
 * Algorithm that helps client self-certification
 * See Deltix FIX Certification Script for details.
 */
public class AutoCertAlgorithm  extends AbstractAlgorithm<AutoCertOrder, SimpleInstrumentPrices> {

    private final int orderScenarioTag;

    AutoCertAlgorithm(AlgorithmContext context, OrdersCacheSettings cacheSettings, int orderScenarioTag) {
        super(context, cacheSettings);

        this.orderScenarioTag = orderScenarioTag;
    }

    @Override
    protected Factory<AutoCertOrder> createParentOrderFactory() {
        return AutoCertOrder::new;
    }

    @Override
    protected Factory<ChildOrder<AutoCertOrder>> createChildOrderFactory() {
        return ChildOrder::new;
    }

    @Override
    protected InstrumentDataFactory<SimpleInstrumentPrices> createInstrumentDataFactory() {
        return SimpleInstrumentPrices::new;
    }

    @Override
    protected void handleNewOrder(AutoCertOrder order, OrderNewRequest request) {
        if (isLeader()) {
            order.setScenario(OrderAttributesParser.getCustomAttribute(request.getAttributes(), orderScenarioTag));
            processScenario(order);

        } else {
            LOGGER.info("Ignoring order %s (not in LEADER role)").with(request.getOrderId());
        }
    }

    private void processScenario(AutoCertOrder order) {
        while (order.nextScenarioEventIndex < order.scenario.length()) {
            int begin = order.nextScenarioEventIndex;
            int end = CharSequenceUtil.indexOf(order.scenario, order.nextScenarioEventIndex, ',');
            if (end < 0)
                end = order.scenario.length();
            order.nextScenarioEventIndex = end + 1;
            if (end > 0) {
                if(processScenarioEvent(order, begin, end))
                    break; // to be continued on timer
            }
        }
    }

    //TODO: This method allocates

    private boolean processScenarioEvent(AutoCertOrder order, int begin, int end) {
        String eventType, eventParam;
        int paramsDelim = CharSequenceUtil.indexOf(order.scenario, begin, ':');
        if (paramsDelim > 0 && paramsDelim < end) {
            eventType = order.scenario.subSequence(begin, paramsDelim);
            eventParam = order.scenario.subSequence(paramsDelim + 1, end);
        } else {
            eventType = order.scenario.subSequence(begin, end);
            eventParam = null;
        }
        switch (eventType) {
            case "ACK":
                sendPendingNewEvent(order);
                break;
            case "OPEN":
                sendNewEvent(order);
                break;
            case "CANCEL":
                sendCancelEvent(order, ! CharSequenceUtil.isEmptyOrNull(eventParam) ? eventParam : "Cancel by AutoCert");
                break;
            case "REJECT":
                sendRejectEvent(order, ! CharSequenceUtil.isEmptyOrNull(eventParam) ? eventParam : "Reject by AutoCert");
                break;
            case "FILL":
                @Decimal long tradePrice;
                @Decimal long tradeQuantity;
                if (eventParam == null) {
                    tradeQuantity = order.getRemainingQuantity();
                    tradePrice = tradePrice(order);
                } else {
                    int priceDelim = eventParam.indexOf(':');
                    if (priceDelim < 0 || priceDelim > end) {
                        tradeQuantity = Decimal64Utils.parse(eventParam);
                        tradePrice = tradePrice(order);
                    } else {
                        tradeQuantity = Decimal64Utils.parse(eventParam, 0, priceDelim);
                        tradePrice = Decimal64Utils.parse(eventParam, priceDelim+1);
                    }
                }
                sendTradeEvent(order, tradeQuantity, tradePrice);
                break;
            case "REJECT-CANCEL":
                order.rejectCancelRequests = true;
                break;
            case "DELAY":
                int delay = (eventParam == null) ? 1000 : OrderAttributesParser.parseInt(eventParam);
                order.delayTimerJob = getTimer().schedule(getClock().time() + delay, orderDelayCallback, order);
                return true; // interrupt script execution

            default:
                System.out.println("Unknown command:" + eventType);
        }
        return false;
    }

    @Override
    protected void handleCancel(AutoCertOrder order, OrderCancelRequest request) {
        if (order.rejectCancelRequests) {
            sendCancelRejectEvent(order, "Rejecting due to REJECT-CANCEL mode", request.getRequestId());
        } else {
            if (order.isFinal()) {
                sendCancelRejectEvent(order, "Order is in final state", request.getRequestId());
            } else {
                sendCancelEvent(order, "Cancelled by user request", request.getRequestId());
            }
        }
    }

    @Override
    protected void handleReplace(AutoCertOrder order, OrderReplaceRequest request) {
        if (order.rejectCancelRequests) {
            sendReplaceRejectEvent(order, request, "Rejecting due to REJECT-CANCEL mode");
        } else {
            if (order.isFinal()) {
                sendReplaceRejectEvent(order, request, "Order is in final state");
            } else {
                sendReplaceEvent(order, request);
                order.setScenario(OrderAttributesParser.getCustomAttribute(request.getAttributes(), orderScenarioTag));
                processScenario(order);
            }
        }
    }

    private long onOrderDelay(long now, AutoCertOrder order) {
        processScenario (order); // resume processing
        return TimerCallback.DO_NOT_RESCHEDULE;
    }

    private final TimerCallback<AutoCertOrder> orderDelayCallback = this::onOrderDelay;

    @Decimal
    private static long tradePrice(AlgoOrder order) {
        return Decimal64Utils.isNaN(order.getFirstOrder().getLimitPrice()) ? Decimal64Utils.fromDouble(123.45) : order.getFirstOrder().getLimitPrice();
    }

}
