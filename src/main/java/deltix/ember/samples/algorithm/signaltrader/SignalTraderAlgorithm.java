package deltix.ember.samples.algorithm.signaltrader;

import deltix.anvil.util.AsciiStringBuilder;
import deltix.anvil.util.Factory;
import deltix.anvil.util.annotation.Alphanumeric;
import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.ember.message.trade.*;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.md.InstrumentDataFactory;
import deltix.ember.service.algorithm.v2.AbstractL1TradingAlgorithm;
import deltix.ember.service.algorithm.v2.OutboundOrderProcessorImpl;
import deltix.ember.service.algorithm.v2.order.OrderEntryReq;
import deltix.ember.service.algorithm.v2.order.OutboundOrder;
import deltix.ember.service.data.OrderState;
import deltix.ember.service.oms.cache.OrdersCacheSettings;
import deltix.timebase.api.messages.universal.TradeEntry;

import javax.annotation.Nonnull;

//TODO: Check Invariant - size of STOP or EXIT order should not exceed filled size of ENTER order
//TODO: Align prices to tick size
//TODO: Some guard against systematic problem with closing positions (somehow EXIT/STOP orders never fill completely)

class OrderGroup {
    SignalOrder enterOrder;

    // The Exit Order, whose contracts match the # of Entry Order contracts filled (AKA Take Profit order)
    SignalOrder exitOrder;

    // The Stop Order, whose contracts match the # of Entry Order contracts filled
    SignalOrder stopOrder;

    boolean isActive() {
        return enterOrder != null && (enterOrder.isActive() || enterOrder.getState() == OrderState.COMPLETELY_FILLED) ||
               exitOrder != null && exitOrder.isActive() ||
               stopOrder != null && stopOrder.isActive();
    }

    void onEnterOrder(SignalOrder order) {
        assert ! isActive();
        assert enterOrder == null : "enter";
        assert exitOrder == null : "exit";
        assert stopOrder == null : "stop";

        enterOrder = order;
    }

    void onStopOrder(SignalOrder order) {
//        assert ! isActive();
        assert enterOrder != null : "enter";
        assert stopOrder == null : "stop";

        stopOrder = order;
    }

    void onExitOrder(SignalOrder order) {
//        assert ! isActive();
        assert enterOrder != null : "enter";
        assert exitOrder == null : "exit";

        exitOrder = order;
    }


    boolean isEnterOrder(SignalOrder order) {
        return enterOrder == order;
    }

    boolean isExitOrder(SignalOrder order) {
        return exitOrder == order;
    }

    boolean isStopOrder(SignalOrder order) {
        return stopOrder == order;
    }

    void reset() {
        enterOrder = null;
        exitOrder = null;
        stopOrder = null;
    }

    @Decimal long computeOverfill() {
        assert isActive();
        assert enterOrder != null;

        @Decimal long enterSize = enterOrder.getTotalExecutedQuantity();
        @Decimal long closeSize = Decimal64Utils.add(
                (exitOrder != null) ? exitOrder.getTotalExecutedQuantity() : Decimal64Utils.ZERO,
                (stopOrder != null) ? stopOrder.getTotalExecutedQuantity() : Decimal64Utils.ZERO);

        @Decimal long overfill = Decimal64Utils.subtract(closeSize, enterSize);
        return Decimal64Utils.max(Decimal64Utils.ZERO, overfill);
    }
}

class SignalOrder extends OutboundOrder {
//    OrderGroup group;
//
//    @Override
//    public void onEvict() {
//        if (group != null) {
//            group.onEvict(this);
//            group = null;
//        }
//        super.onEvict();
//    }

}


public class SignalTraderAlgorithm extends AbstractL1TradingAlgorithm<InstrumentState, SignalOrder> {
    private static final long MAX_ORDER_SIZE = Decimal64Utils.fromLong(10);
    private final SignalTraderSettings settings;
    private final AsciiStringBuilder orderReason = new AsciiStringBuilder(64);

    // Per spec only one order group will be processed at a time
    private OrderGroup orderGroup = new OrderGroup();
    private String abortReason;

    private int maximumNumberOfSignals = 3;

    SignalTraderAlgorithm(AlgorithmContext context, OrdersCacheSettings cacheSettings, SignalTraderSettings settings) {
        super(context, cacheSettings);
        this.settings = settings;
    }


    private boolean canEnterTrading() {
        if (abortReason != null)
            return false;

        assert ! orderGroup.isActive();


        return true; //TODO: We only allow our system to trade during specific hours of the day
    }

    @Override
    public void onException(Throwable e) {
        LOGGER.error("Unexpected exception:%s ").with(e);
        abortReason = "Aborted due to unexpected exception";
    }

    /** Paranoidal checks for the first version */
    private boolean checkSafety (@Decimal long enterSize) {

        if (maximumNumberOfSignals > 0) {
            maximumNumberOfSignals--;
        } else {
            abortReason = "Maximum number of signals processed";
            return false;
        }

        return Decimal64Utils.isLessOrEqual(Decimal64Utils.abs(enterSize), MAX_ORDER_SIZE); //TODO: remove after initial testing
    }


/// region Market data

    @Override
    protected InstrumentDataFactory<InstrumentState> createInstrumentDataFactory() {
        return (symbol, instrumentType) -> new InstrumentState(symbol, instrumentType, settings) {

            @Override
            protected void onTradeMessage(TradeEntry message) {
                super.onTradeMessage(message);

                smav.register(Decimal64Utils.toDouble(message.getPrice())); // sample only

                if (orderGroup.isActive()) {
                    checkEnterOrderPrice (message.getPrice());
                } else {
                    if (canEnterTrading()) {
                        assert !orderGroup.isActive();

                        orderReason.clear(); //Just a sample of recording order reason, can also be set into order's UserData field or custom order attribute

                        @Decimal long orderSize = checkSignal(message, orderReason);
                        if ( ! Decimal64Utils.isZero(orderSize) && checkSafety(orderSize)) { //TODO: Check order size is too small

                            SignalOrder enterOrder = submitNewEnterOrderRequest(getSymbol(), orderSize, message.getPrice(), message.getExchangeId());
                            orderGroup.onEnterOrder(enterOrder);
                            LOGGER.info("Sent %s %s %s @ %s Reason:%s")
                                    .with(enterOrder.getSide())
                                    .with(enterOrder.getSymbol())
                                    .withDecimal64(enterOrder.getWorkingQuantity())
                                    .withDecimal64(enterOrder.getLastOrder().getLimitPrice())
                                    .with(orderReason);

                            assert orderGroup.isActive();
                        }
                    }
                }
            }

            /** @return ENTER order size (positive for BUY, negative for SELL), or zero if signal condition is not met */
            @Decimal
            private long checkSignal(TradeEntry trade, AsciiStringBuilder reason) {
                assert ! orderGroup.isActive();
                @Decimal long result = Decimal64Utils.ZERO;
                if (smav.isFull()) { // Customize this: math below are for sample purpose only (bollinger band):
                    final double mp = smav.getAverage();
                    final double sqrtVariance = smav.getStdDev();
                    final double upperBand = mp + settings.getNumStdDevs() * sqrtVariance;
                    final double bottomBand = mp - settings.getNumStdDevs() * sqrtVariance;

                    // rules for position opening
                    double price = Decimal64Utils.toDouble(trade.getPrice());
                    if (price > upperBand) {
                        reason.append("Above ").append(upperBand).append(" band");
                        result = Decimal64Utils.negate(Decimal64Utils.multiply(trade.getSize(), settings.getEnterOrderSizeCoefficient()));
                    } else
                    if (price < bottomBand) {
                        reason.append("Below ").append(bottomBand).append(" band");
                        result = Decimal64Utils.multiply(trade.getSize(), settings.getEnterOrderSizeCoefficient());
                    }
                }
                return result;
            }

        };
    }

    /// endregion

    /// region Trading

    @Override
    protected Factory<SignalOrder> createOrderFactory() {
        return SignalOrder::new;
    }

    @Override
    protected OutboundOrderProcessorImpl<SignalOrder> createOutboundOrderProcessor(AlgorithmContext context, OrdersCacheSettings cacheSettings) {
        return new SignalTraderOrderProcessor(context, cacheSettings, createOrderFactory(), createOrderEntryReqFactory());
    }

    class SignalTraderOrderProcessor extends OutboundOrderProcessorImpl<SignalOrder> {

        SignalTraderOrderProcessor(AlgorithmContext context, OrdersCacheSettings cacheSettings, Factory<SignalOrder> orderFactory, Factory<OrderEntryReq> entryReqFactory) {
            super(context, cacheSettings, orderFactory, entryReqFactory);
        }

        /** Process fill events of our orders */
        @Override
        public void onTradeReport(SignalOrder order, OrderTradeReportEvent event) {
            super.onTradeReport(order, event);

            InstrumentState instrumentInfo = get(order.getSymbol()); // should always be true unless we see echo of external trades
            if (instrumentInfo != null) {
                instrumentInfo.updateInstrument(event.getSide(), event.getTradePrice(), event.getTradeQuantity());
                LOGGER.info("Position changed: %s").with(instrumentInfo);
                if (orderGroup.isActive()) { // should always be true unless we see echo of external trades
                    if (orderGroup.isEnterOrder(order)) {
                        increaseExitAndStopOrders();
                    } else {
                        decreaseExitOrStopOrders(order, event.getTradeQuantity());
                    }
                } else {
                    LOGGER.warn("Fill %s for inactive order group").with(order);
                }
            } else {
                LOGGER.warn("Fill %s for unknown symbol %s").with(order).with(order.getSymbol());
            }
        }

        @Override
        public void onOrderReject(SignalOrder order, OrderRejectEvent event) {
            super.onOrderReject(order, event);

            if (orderGroup.isActive()) { // should always be true unless we see echo of external trades
                LOGGER.warn("Reject for order %s - suspending algo: %s [%s:%s]").with(order).with(event.getReason()).with(event.getDeltixRejectCode()).with(event.getVendorRejectCode());
                abortOrderGroup("Unexpected order reject");
            } else {
                LOGGER.warn("Reject %s for inactive order group").with(order);
            }
        }

        @Override
        public void onOrderCancel(SignalOrder order, OrderCancelEvent event) {
            final boolean wasActive = orderGroup.isActive();

            super.onOrderCancel(order, event);

            if(wasActive && ! orderGroup.isActive()) {
                orderGroup.reset();
            }
        }

        //TODO: Cancel Reject
        //TODO: Replace Reject
    }

    private void abortOrderGroup(@Nonnull String reason) {
        assert orderGroup.isActive();
        abortReason = reason;
        if (orderGroup.enterOrder != null && orderGroup.enterOrder.isActive())
            cancel(orderGroup.enterOrder, reason);
        if (orderGroup.exitOrder != null && orderGroup.exitOrder.isActive())
            cancel(orderGroup.exitOrder, reason);
        if (orderGroup.stopOrder != null && orderGroup.stopOrder.isActive())
            cancel(orderGroup.stopOrder, reason);
    }

    private void decreaseExitOrStopOrders(SignalOrder order, @Decimal long tradeQuantity) {
        assert orderGroup.isActive();
        assert ! orderGroup.isEnterOrder(order);
        if (orderGroup.isExitOrder(order)) {
            if (orderGroup.stopOrder.isActive()) {
                @Decimal long newQuantity = Decimal64Utils.subtract(orderGroup.stopOrder.getLastOrder().getQuantity(), tradeQuantity);
                if (Decimal64Utils.isPositive(newQuantity)) {


//* If the market begins trading at the Exit Order price, the Stop Order is modified to a price closer to the Exit price, in order to minimize impact should the Exit Order not fill (C3)
//* If certain conditions are reached (which we have dubbed "Trigger Prices"), the Exit Order will be modified to a new price (C4)


                    replaceStopOrderRequest(orderGroup.stopOrder, newQuantity);
                } else {
                    cancel(orderGroup.stopOrder, "Exit");
                    submitOverfillFixIfNecessary();
                }
            } else {
                submitOverfillFixIfNecessary(); // EXIT order filled while STOP order is already inactive
            }
        } else if (orderGroup.isStopOrder(order)) {
            @Decimal long newQuantity = Decimal64Utils.subtract(orderGroup.exitOrder.getLastOrder().getQuantity(), tradeQuantity);
            if (orderGroup.exitOrder.isActive()) {
                if (Decimal64Utils.isPositive(newQuantity)) {
                    replaceExitOrderRequest(orderGroup.exitOrder, newQuantity);
                } else {
                    cancel(orderGroup.exitOrder, "Stop");
                    submitOverfillFixIfNecessary();
                }
            } else {
                submitOverfillFixIfNecessary(); // STOP order filled while EXIT order is already inactive
            }
        } else {
            LOGGER.warn("Fill %s for unexpected order %s (not one of group)").with(order);
        }
    }

    private void submitOverfillFixIfNecessary() {
        assert orderGroup.isActive();
        @Decimal long overfillQuantity = orderGroup.computeOverfill();
        if (Decimal64Utils.isPositive(overfillQuantity))
            submitOverfillFixOrderRequest (overfillQuantity);
    }

    private void increaseExitAndStopOrders() {
        @Decimal long newQuantity = orderGroup.enterOrder.getTotalExecutedQuantity();
        assert Decimal64Utils.isLessOrEqual(newQuantity, orderGroup.enterOrder.getWorkingQuantity());
        if (orderGroup.exitOrder == null) {
            assert orderGroup.stopOrder == null;
            orderGroup.onExitOrder(submitNewExitOrderRequest(orderGroup.enterOrder, newQuantity));
        } else {
            assert orderGroup.stopOrder != null;
            if (orderGroup.exitOrder.isActive()) {
                replaceExitOrderRequest(orderGroup.exitOrder, newQuantity);
            } else {
                LOGGER.warn("Unexpected fill for ENTER order while EXIT order is INACTIVE").with(orderGroup.exitOrder);
            }
        }

        if (orderGroup.stopOrder == null) {
            orderGroup.onStopOrder(submitNewStopOrderRequest(orderGroup.enterOrder, newQuantity));
        } else {
            if (orderGroup.stopOrder.isActive()) {
                replaceStopOrderRequest(orderGroup.stopOrder, newQuantity);
            } else {
                //TODO: this is actually possible in fast moving market (STOP may be triggered and filled before ENTER order is completely filled?)
                LOGGER.warn("Unexpected fill for ENTER order while STOP order is INACTIVE").with(orderGroup.stopOrder);
            }
        }
    }

    /** if ENTER order is still active and price cross STOP or EXIT price cancel ENTER order */
    private void checkEnterOrderPrice(@Decimal long marketPrice) {
        // 1. If the Abort price is reached before the Entry Order fills any contracts, the working Entry Order is cancelled
        // 2. Unfilled Entry Order contracts are cancelled when the Exit Price is reached
        assert orderGroup.isActive();

        SignalOrder enterOrder = orderGroup.enterOrder; assert enterOrder != null;
        if (enterOrder.isActive()) {
            boolean cancelEnter;
            if (enterOrder.getSide() == Side.BUY)
                cancelEnter = Decimal64Utils.isLessOrEqual(marketPrice, getStopPrice(enterOrder)) ||
                              Decimal64Utils.isGreaterOrEqual(marketPrice, getExitPrice(enterOrder));
            else
                cancelEnter = Decimal64Utils.isGreaterOrEqual(marketPrice, getStopPrice(enterOrder)) ||
                              Decimal64Utils.isLessOrEqual(marketPrice, getExitPrice(enterOrder));
            if (cancelEnter)
                cancel(enterOrder, "Stop price cross");
        }
    }

    /** Customize this: here we compute limit price of EXIT order */
    private @Decimal long getExitPrice(SignalOrder enterOrder) {
        @Decimal long limitPrice = enterOrder.getAvgExecutedPrice();
        @Decimal long offset = Decimal64Utils.multiply(limitPrice, settings.getExitOrderPriceCoefficient());
        return (enterOrder.getSide() == Side.BUY) ? Decimal64Utils.add(limitPrice, offset) : Decimal64Utils.subtract(limitPrice, offset);
    }

    /** Customize this: here we compute stop price of STOP order */
    private @Decimal long getStopPrice(SignalOrder enterOrder) {
        @Decimal long limitPrice = enterOrder.getAvgExecutedPrice();
        @Decimal long offset = Decimal64Utils.multiply(limitPrice, settings.getStopOrderPriceCoefficient());
        return (enterOrder.getSide() == Side.BUY) ? Decimal64Utils.subtract(limitPrice, offset) : Decimal64Utils.add(limitPrice, offset);
    }

    /** "Enter" Order parameters (Prepare a Limit order at which we "Enter" the market) */
    private SignalOrder submitNewEnterOrderRequest (CharSequence symbol, @Decimal long orderSize, @Decimal long limitPrice, @Alphanumeric long exchangeCode) {
        assert orderGroup.enterOrder == null;
        MutableOrderNewRequest result = orderProcessor.makeSubmitRequest();
        result.setQuantity(Decimal64Utils.abs(orderSize));
        result.setSide(Decimal64Utils.isPositive(orderSize) ? Side.BUY : Side.SELL);
        result.setLimitPrice(limitPrice);
        result.setSymbol(symbol);
        result.setOrderType(OrderType.LIMIT);
        result.setExchangeId(exchangeCode);
        //result.setDestinationId(destinationId);
        return submit(result);
    }

    /** "Exit" Order parameters (Exit Order, which is a Limit Order, intended to offset/flatten the Entry Order and yield positive earnings) */
    private SignalOrder submitNewExitOrderRequest (SignalOrder enterOrder, @Decimal long orderSize) {
        assert orderGroup.exitOrder == null;
        MutableOrderNewRequest result = orderProcessor.makeSubmitRequest();
        result.setQuantity(Decimal64Utils.abs(orderSize));
        result.setSide((enterOrder.getSide() == Side.BUY) ? Side.SELL: Side.BUY);
        result.setLimitPrice(getExitPrice (enterOrder));
        result.setSymbol(enterOrder.getSymbol());
        result.setOrderType(OrderType.LIMIT);
        result.setExchangeId(enterOrder.getExchangeId());
        //result.setDestinationId(destinationId);
        return submit(result);
    }

    /** "Stop" Order parameters (Stop Order, which is a Stop Market order, intended to prevent excessive losses if the prediction is incorrect) */
    private SignalOrder submitNewStopOrderRequest (SignalOrder enterOrder, @Decimal long orderSize) {
        assert orderGroup.stopOrder == null;
        MutableOrderNewRequest result = orderProcessor.makeSubmitRequest();
        result.setQuantity(Decimal64Utils.abs(orderSize));
        result.setSide((enterOrder.getSide() == Side.BUY) ? Side.SELL: Side.BUY);
        result.setLimitPrice(getStopPrice (enterOrder));
        result.setSymbol(enterOrder.getSymbol());
        result.setOrderType(OrderType.STOP);
        result.setExchangeId(enterOrder.getExchangeId());
        //result.setDestinationId(destinationId);
        return submit(result);
    }

    private void replaceStopOrderRequest (SignalOrder stopOrder, @Decimal long newQuantity) {
        replaceStopOrderRequest(stopOrder, newQuantity, stopOrder.getLastOrder().getStopPrice());
    }

    private void replaceStopOrderRequest (SignalOrder stopOrder, @Decimal long newQuantity, @Decimal long newStopPrice) {
        MutableOrderReplaceRequest result = orderProcessor.makeReplaceRequest(stopOrder);
        result.setQuantity(newQuantity);
        result.setSide(stopOrder.getSide());
        result.setStopPrice(newStopPrice);
        result.setSymbol(stopOrder.getSymbol());
        result.setOrderType(OrderType.STOP);
        result.setExchangeId(stopOrder.getExchangeId());
        //result.setDestinationId(destinationId);
        replace(stopOrder, result);
    }

    private void replaceExitOrderRequest (SignalOrder exitOrder, @Decimal long newQuantity) {
        MutableOrderReplaceRequest result = orderProcessor.makeReplaceRequest(exitOrder);
        result.setQuantity(newQuantity);
        result.setSide(exitOrder.getSide());
        result.setStopPrice(exitOrder.getLastOrder().getLimitPrice());
        result.setSymbol(exitOrder.getSymbol());
        result.setOrderType(OrderType.LIMIT);
        result.setExchangeId(exitOrder.getExchangeId());
        //result.setDestinationId(destinationId);
        replace(exitOrder, result);
    }

    private SignalOrder submitOverfillFixOrderRequest (@Decimal long overfillAmount) {
        assert orderGroup.isActive();
        assert Decimal64Utils.isLessOrEqual(overfillAmount, orderGroup.enterOrder.getWorkingQuantity());
        MutableOrderNewRequest result = orderProcessor.makeSubmitRequest();
        result.setQuantity(overfillAmount);
        result.setSide(orderGroup.enterOrder.getSide());
        result.setSymbol(orderGroup.enterOrder.getSymbol());
        result.setOrderType(OrderType.MARKET);
        result.setExchangeId(orderGroup.enterOrder.getExchangeId());
        //result.setDestinationId(destinationId);
        return submit(result);
    }

    /// endregion

}
