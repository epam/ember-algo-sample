package deltix.ember.samples.algorithm.arbitrage;

import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.anvil.util.CharSequenceUtil;
import deltix.anvil.util.annotation.Alphanumeric;
import deltix.anvil.util.annotation.Timestamp;
import deltix.ember.message.smd.InstrumentType;
import deltix.ember.message.trade.OrderCancelEvent;
import deltix.ember.message.trade.OrderRejectEvent;
import deltix.ember.message.trade.OrderTradeReportEvent;
import deltix.ember.message.trade.Side;
import deltix.ember.service.algorithm.util.OrderBookHelper;
import deltix.ember.service.algorithm.v2.AbstractL2TradingAlgorithm;
import deltix.ember.service.algorithm.v2.order.OutboundOrder;
import com.epam.deltix.gflog.api.Log;
import deltix.orderbook.core.api.OrderBook;
import deltix.orderbook.core.api.OrderBookFactory;
import deltix.orderbook.core.api.OrderBookQuote;
import deltix.orderbook.core.options.*;
import deltix.qsrv.hf.pub.InstrumentMessage;

import deltix.timebase.api.messages.DataModelType;
import deltix.timebase.api.messages.QuoteSide;
import deltix.timebase.api.messages.universal.BaseEntryInfo;
import deltix.timebase.api.messages.universal.PackageHeaderInfo;
import deltix.timebase.api.messages.universal.TradeEntryInfo;
import deltix.util.collections.generated.ObjectList;

/**
 * ArbitrageHandler monitors quotes from entry exchange and trades from exit exchange
 * On each trade if current best asking price > last trade price we enter position on entry exchange by targeting the quote
 * and then exit position on exit exchange by issuing a passive limit order
 *
 * For each instrument this handler can be in one of three states: { MONITORING, ENTERING, EXITING }.
 *
 * MONITORING - looking for opportunity to open position
 * ENTERING - opening position (entryOrder is not NULL)
 * EXITING - exiting (hedging) position (exitOrder is not NULL)
 */
public class ArbitrageHandler extends AbstractL2TradingAlgorithm.OrderBookState {

    private static final long MIN_REPLACE_INTERVAL = 100;

    private final Log logger;
    private final ArbitrageAlgorithm algorithm;
    private final boolean arbitrageEnabled;
    private final @Alphanumeric long entryExchangeId;
    private final @Alphanumeric long exitExchangeId; // exit exchange

    private @Timestamp long lastTradeTime = 0;
    private @Decimal long lastTradePrice = Decimal64Utils.NULL;
    private @Timestamp long lastReplaceTime = 0;

    private OutboundOrder entryOrder; // Warning: don't forget to release all reference to the orders after they reach final state (orders are recycled to object pool)
    private OutboundOrder exitOrder; // Warning: don't forget to release all reference to the orders after they reach final state (orders are recycled to object pool)

    public ArbitrageHandler(CharSequence symbol, InstrumentType instrumentType, ArbitrageAlgorithm algorithm, Log logger) {
        super(symbol, instrumentType);
        this.algorithm = algorithm;
        this.logger = logger;

        arbitrageEnabled = algorithm.isSubscribed(getSymbol());

        ArbitrageSettings settings = algorithm.getSettings();
        entryExchangeId = settings.getEntryExchange();
        exitExchangeId = settings.getExitExchange();
    }


    @Override
    protected OrderBook<OrderBookQuote> createOrderBook(String symbol) {
        final OrderBookOptions COMMON_ORDER_BOOK_OPTIONS = new OrderBookOptionsBuilder()
                .quoteLevels(DataModelType.LEVEL_TWO)
                .updateMode(UpdateMode.WAITING_FOR_SNAPSHOT)
                .disconnectMode(DisconnectMode.CLEAR_EXCHANGE)
                .shouldStoreQuoteTimestamps(true)
                .build();

        final OrderBookOptions orderBookOptions = new OrderBookOptionsBuilder()
                .parent(COMMON_ORDER_BOOK_OPTIONS)
                .orderBookType(OrderBookType.CONSOLIDATED)
                .symbol(symbol)
                .build();

        return OrderBookFactory.create(orderBookOptions);
    }

    /** Handles changes on the market */
    @Override
    public void onMarketMessage(InstrumentMessage message) {
        super.onMarketMessage(message);

        if (! arbitrageEnabled)
            return;

        if (message instanceof PackageHeaderInfo) {
            PackageHeaderInfo p = (PackageHeaderInfo) message;
            if (p.hasEntries()) {
                ObjectList<BaseEntryInfo> entries = p.getEntries();
                for (int i = 0; i < entries.size(); i++) {
                    BaseEntryInfo entry = entries.get(i);
                    if (entry.getExchangeId() == exitExchangeId && entry instanceof TradeEntryInfo) {
                        TradeEntryInfo tradeEntry = (TradeEntryInfo) entry;
                        updateTrade(message.getTimeStampMs(), tradeEntry.getPrice());
                    }
                }
            }
        }

        if (! algorithm.isLeaderNode())
            return;

        if (isMonitoring()) {
            // check if we need to enter position
            // find the best ask on entry exchange order book
            OrderBookQuote bestAsk = OrderBookHelper.getBestQuote(orderBook, entryExchangeId, QuoteSide.ASK);
            // if the last trade price on exit exchange is grater than the ask quote and trade is more recent
            if (bestAsk != null && Decimal64Utils.isGreater(lastTradePrice, bestAsk.getPrice()) && lastTradeTime > bestAsk.getTimestamp()) {
                // try to enter position at best ask quote price
                entryOrder = algorithm.submitEntryOrder(getSymbol(), bestAsk.getSize(), bestAsk.getPrice(), entryExchangeId);
                logger.info("Submitted entry order: %s").with(entryOrder);
            }

        } else if (isExiting() && !exitOrder.isFinal() && !exitOrder.isReplacePending() && algorithm.getTime() >= lastReplaceTime + MIN_REPLACE_INTERVAL) {
            // chase the market if it moved away
            OrderBookQuote bestAsk = OrderBookHelper.getBestQuote(orderBook, exitExchangeId, QuoteSide.ASK);
            if (bestAsk != null && Decimal64Utils.isLess(bestAsk.getPrice(), exitOrder.getWorkingOrder().getLimitPrice())) {
                @Decimal long limitPrice = bestAsk.getPrice();
                algorithm.replaceExitOrder(exitOrder, limitPrice);
                lastReplaceTime = algorithm.getTime();
                logger.info("Updating exit order %s with new price: %s").with(exitOrder.getCorrelationOrderId()).withDecimal64(limitPrice);
            }
        }
    }

    private void updateTrade(long tradeTime, @Decimal long tradePrice) {
        if (tradeTime >= lastTradeTime) {
            lastTradeTime = tradeTime;
            lastTradePrice = tradePrice;
        }
    }

    public void onFilled(OutboundOrder order, OrderTradeReportEvent event) {
        if (isEntering() && order == entryOrder) {
            // ignore partial fills
            if (! order.isFinal()) {
                logger.info("Received partial fill %s for %s").withDecimal64(event.getTradeQuantity()).with(order);
                return;
            }

            // if order is entry order that was filled issue passive order at best ask on exit exchange to exit position
            OrderBookQuote bestAsk = OrderBookHelper.getBestQuote(orderBook, exitExchangeId, QuoteSide.ASK);
            if (bestAsk == null) {
                logger.warn("No %s market on exchange %s").with(getSymbol()).withAlphanumeric(exitExchangeId);
            }
            @Decimal long limitPrice = (bestAsk != null) ? bestAsk.getPrice() : lastTradePrice;
            exitOrder = algorithm.submitExitOrder(entryOrder, limitPrice, exitExchangeId);
            logger.info("Submitted exit order: %s").with(exitOrder);

        } else if (isExiting() && order == exitOrder) {
            // ignore partial fills
            if (! order.isFinal()) {
                logger.info("Received partial fill %s for %s").withDecimal64(event.getTradeQuantity()).with(order);
                return;
            }
            // just exited position so reset to start monitoring again
            reset();
            logger.info("Exited position: %s").with(order);
        } else {
            logger.warn("Unexpected trade received for %s").with(order);
        }
    }

    public void onCanceled(OutboundOrder order, OrderCancelEvent event) {
        if (isEntering() && order == entryOrder) {
            // if partially filled exit position for partial quantity
            if (Decimal64Utils.isPositive(order.getTotalExecutedQuantity())) {
                // find best ask on taker order book
                OrderBookQuote bestAsk = OrderBookHelper.getBestQuote(orderBook, exitExchangeId, QuoteSide.ASK);
                if (bestAsk == null) {
                    logger.warn("No %s market on exchange %s").with(getSymbol()).withAlphanumeric(exitExchangeId);
                }
                @Decimal long limitPrice = (bestAsk != null) ? bestAsk.getPrice() : entryOrder.getAvgExecutedPrice();
                exitOrder = algorithm.submitExitOrder(entryOrder, limitPrice, exitExchangeId);
                logger.info("Submitted exit order: %s").with(exitOrder);

            } else {
                // IOC entry order was fully canceled - start monitoring again
                logger.info("Entry order was canceled: %s").with(entryOrder);
                reset();
            }

        } else if (isExiting() && order == exitOrder) {
            logger.warn("Exit order %s canceled: %s").with(order.getCorrelationOrderId()).with(event);
            @Decimal long remainingQuantity = Decimal64Utils.subtract(order.getWorkingQuantity(), order.getTotalExecutedQuantity());
            if (Decimal64Utils.isPositive(remainingQuantity)) {
                logger.warn("Failed to exit position: %s %s remaining").withDecimal64(remainingQuantity).with(getSymbol());
            } else {
                reset();
            }
        } else {
            logger.warn("Unexpected cancel received for %s").with(order);
        }
    }

    public void onRejected(OutboundOrder order, OrderRejectEvent event) {
        if (isEntering() && order == entryOrder) {
            logger.info("Entry order was rejected: %s").with(entryOrder);
            reset();
        } else if (isExiting() && order == exitOrder) {
            logger.warn("Exit order %s rejected: %s").with(order.getCorrelationOrderId()).with(event);
            @Decimal long remainingQuantity = Decimal64Utils.subtract(order.getWorkingQuantity(), order.getTotalExecutedQuantity());
            if (Decimal64Utils.isPositive(remainingQuantity)) {
                logger.warn("Failed to exit position: %s %s remaining").withDecimal64(remainingQuantity).with(getSymbol());
            } else {
                reset();
            }
        } else {
            logger.warn("Unexpected reject received for %s").with(order);
        }
    }

    public void onLeaderState(OutboundOrder order) {
        assert order.isActive();
        assert isMonitoring();
        assert CharSequenceUtil.equals(order.getSymbol(), getSymbol());

        if (order.getSide() == Side.BUY)
            entryOrder = order;
        else
            exitOrder = order;
    }

    // reset back to Monitoring state
    private void reset() {
        assert (entryOrder == null || entryOrder.isFinal());
        assert (exitOrder == null || exitOrder.isFinal());

        entryOrder = null;
        exitOrder = null;
        lastTradeTime = 0;
        lastTradePrice = Decimal64Utils.NULL;
        lastReplaceTime = 0;
    }

    public boolean isMonitoring() {
        return entryOrder == null && exitOrder == null;
    }

    public boolean isEntering() {
        return entryOrder != null && exitOrder == null;
    }

    public boolean isExiting() {
        return exitOrder != null;
    }
}
