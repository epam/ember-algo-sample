package deltix.ember.samples.algorithm.marketmaker;

import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import com.epam.deltix.gflog.api.Log;
import deltix.anvil.util.CharSequenceUtil;
import deltix.anvil.util.annotation.Alphanumeric;
import deltix.anvil.util.annotation.Timestamp;
import deltix.ember.message.smd.InstrumentType;
import deltix.ember.message.smd.InstrumentUpdate;
import deltix.ember.message.trade.*;
import deltix.ember.message.trade.oms.PositionReport;
import deltix.ember.service.algorithm.util.OrderBookHelper;
import deltix.ember.service.algorithm.util.RoundingTools;
import deltix.ember.service.algorithm.v2.AbstractL2TradingAlgorithm;
import deltix.ember.service.algorithm.v2.order.OutboundOrder;
import deltix.orderbook.core.api.OrderBook;
import deltix.orderbook.core.api.OrderBookFactory;
import deltix.orderbook.core.api.OrderBookQuote;
import deltix.orderbook.core.options.*;
import deltix.qsrv.hf.pub.InstrumentMessage;
import deltix.timebase.api.messages.DataModelType;
import deltix.timebase.api.messages.QuoteSide;

import java.util.ArrayList;

public class MarketMakerHandler extends AbstractL2TradingAlgorithm.OrderBookState {
    private final RateLimiter rateLimiter;

    private final Log logger;
    private final MarketMakerAlgorithm algorithm;
    private final boolean isSubscribed;

    @Decimal
    private long currentPosition = Decimal64Utils.ZERO;
    @Decimal
    private long avgPositionPrice = Decimal64Utils.NULL;
    @Decimal
    private long realizedPnL = Decimal64Utils.ZERO;
    @Decimal
    private long openBuyQty = Decimal64Utils.ZERO;
    @Decimal
    private long openSellQty = Decimal64Utils.ZERO;
    @Decimal
    private long openHedgeBuyQty = Decimal64Utils.ZERO; // buyQty and SellQty both cannot be positive
    @Decimal
    private long openHedgeSellQty = Decimal64Utils.ZERO;
    @Decimal
    private long priceIncrement = Decimal64Utils.NULL;
    @Decimal
    private long currentAskBasePrice = Decimal64Utils.NULL;
    @Decimal
    private long currentBidBasePrice = Decimal64Utils.NULL;

    private final OutboundOrder[] activeSellOrders; // Warning: don't forget to release all references to the orders after they reach final state (orders are recycled to object pool)
    private final OutboundOrder[] activeBuyOrders; // Warning: don't forget to release all references to the orders after they reach final state (orders are recycled to object pool)
    private final ArrayList<OutboundOrder> activeHedgingOrders;

    private final @Alphanumeric long exchange;
    private final @Alphanumeric long sourceExchange;

    @Decimal
    private final long[] sellQuoteSizes;
    @Decimal
    private final long[] buyQuoteSizes;
    @Decimal
    private final long[] sellMargins; // round this and basePrice to tick to avoid rounding operations later, though this step can lose precision
    @Decimal
    private final long[] buyMargins;
    @Decimal
    private final long minSpread;
    @Decimal
    private final long minPriceChange;

    @Decimal
    private final long maxLongExposure;
    @Decimal
    private final long maxShortExposure;

    @Decimal
    private final long positionMaxSize;
    @Decimal
    private final long positionNormalSize;

    public MarketMakerHandler(CharSequence symbol, InstrumentType instrumentType, MarketMakerAlgorithm algorithm, Log logger) {
        super(symbol, instrumentType);

        MarketMakerSettings settings = algorithm.getSettings();
        // instrument
        exchange = settings.getExchange();
        sourceExchange = settings.getSourceExchange();

        // pricer
        sellQuoteSizes = convertArrayFromDouble(settings.getSellQuoteSizes());
        buyQuoteSizes = convertArrayFromDouble(settings.getBuyQuoteSizes());
        sellMargins = convertArrayFromDouble(settings.getSellMargins());
        buyMargins = convertArrayFromDouble(settings.getBuyMargins());
        minSpread = Decimal64Utils.fromDouble(settings.getMinSpread());
        minPriceChange = Decimal64Utils.fromDouble(settings.getMinPriceChange());

        // risk limits
        maxShortExposure = Decimal64Utils.fromDouble(settings.getMaxShortExposure());
        maxLongExposure = Decimal64Utils.fromDouble(settings.getMaxLongExposure());

        // hedger
        positionNormalSize = Decimal64Utils.fromDouble(settings.getPositionNormalSize());
        positionMaxSize = Decimal64Utils.fromDouble(settings.getPositionMaxSize());

        this.algorithm = algorithm;
        this.logger = logger;
        this.activeSellOrders = new OutboundOrder[sellQuoteSizes.length];
        this.activeBuyOrders = new OutboundOrder[buyQuoteSizes.length];
        this.activeHedgingOrders = new ArrayList<>();
        this.rateLimiter = new RateLimiter(settings.getRateLimit());
        isSubscribed = algorithm.isSubscribed(getSymbol());
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

    @Override
    public void update(InstrumentUpdate instrumentUpdate) {
        setPriceIncrement(instrumentUpdate.getPriceIncrement());
    }

    /** Handles changes on the market */
    @Override
    public void onMarketMessage(InstrumentMessage message) {
        super.onMarketMessage(message);

        if (!isSubscribed || !algorithm.isReady())
            return;

        currentAskBasePrice = calculateBasePrice(QuoteSide.ASK);
        currentBidBasePrice = calculateBasePrice(QuoteSide.BID);
        if (currentBidBasePrice != Decimal64Utils.NULL && currentAskBasePrice != Decimal64Utils.NULL) {
            assert Decimal64Utils.isGreater(currentAskBasePrice, currentBidBasePrice);
            @Decimal long adjustment = Decimal64Utils.subtract(minSpread, Decimal64Utils.subtract(currentAskBasePrice, currentBidBasePrice));
            if (Decimal64Utils.isPositive(adjustment)) {
                // one more extra parameter being added overrides the default % attribution (50/50 to buy and sell quote arrays) for spread increase
                // see https://kb.marketmaker.cloud/market-maker-application/trading/bot-configuration#min-spread-examples
                @Decimal long halfAdjustment = Decimal64Utils.divide(adjustment, Decimal64Utils.TWO);
                currentAskBasePrice = Decimal64Utils.add(currentAskBasePrice, halfAdjustment);
                currentBidBasePrice = Decimal64Utils.subtract(currentBidBasePrice, halfAdjustment);
            }
            processOrders(Side.SELL);
            processOrders(Side.BUY);
        } // todo: else shutdown algo?
    }

    public void onFilled(OutboundOrder order, OrderTradeReportEvent event) {
        boolean isBuy = order.getSide() == Side.BUY;
        final boolean isHedger = CharSequenceUtil.equals(order.getUserData(), "Hedger");
        @Decimal final long tradeQty = event.getTradeQuantity();

        if (isBuy) {
            openBuyQty = Decimal64Utils.subtract(openBuyQty, tradeQty);
            if (isHedger) {
                openHedgeBuyQty = Decimal64Utils.subtract(openHedgeBuyQty, tradeQty);
            }
        } else {
            openSellQty = Decimal64Utils.subtract(openSellQty, tradeQty);
            if (isHedger) {
                openHedgeSellQty = Decimal64Utils.subtract(openHedgeSellQty, tradeQty);
            }
        }
        updateAllTimePosition(event, isBuy);

        if (order.isFinal())
            removeFromActive(order);

        if (!isHedger) {
            hedgerAction(); // hedging has higher priority
            if (order.isFinal()) {
                processOrders(Side.BUY); // we should call this, because we may need to place better orders
                processOrders(Side.SELL);
            }
        }
    }

    public void onRejected(OutboundOrder order, OrderRejectEvent event) {
        // now it assumes SAFE REJECT
        // because we trust our RateLimiter
        removeFromActive(order);
        processOrders(Side.BUY);
        processOrders(Side.SELL);
    }

    public void onCanceled(OutboundOrder order, OrderCancelEvent event) {
        // SAFE CANCEL
        removeFromActive(order);
        processOrders(Side.BUY);
        processOrders(Side.SELL);
    }

    public void onNew(OutboundOrder order, OrderNewEvent event) {
    }

    public void updatePosition(PositionReport response) {
        currentPosition = response.getSize();
        realizedPnL = response.getRealizedPnL();
        logger.info("Position: %s").withDecimal64(currentPosition);
        logger.info("PnL: %s").withDecimal64(realizedPnL);
        logger.info("OpenBuyQty: %s").withDecimal64(response.getOpenBuySize());
        logger.info("OpenSellQty: %s").withDecimal64(response.getOpenSellSize());
    }

    private void processOrders(Side side) {
        if (currentAskBasePrice == Decimal64Utils.NULL || currentBidBasePrice == Decimal64Utils.NULL)
            return;

        OutboundOrder[] orders = (side == Side.BUY) ? activeBuyOrders : activeSellOrders;
        final int len = ((side == Side.BUY) ? buyQuoteSizes : sellQuoteSizes).length;

        for (int i = 0; i < len; i++) {
            @Decimal final long size = getSize(i, side);
            @Decimal final long price = getPrice(i, side);

            OutboundOrder order = orders[i];
            if (order != null) {
                if (order.isCancelPending())
                    continue;
                // check if we really need to replace via min price/size change
                boolean priceThreshold = Decimal64Utils.isGreater(Decimal64Utils.abs(Decimal64Utils.subtract(order.getWorkingOrder().getLimitPrice(), price)), minPriceChange);
                // size is probably only useful in REPLICATION source aggregation method
                boolean sizeThreshold = false;
                if ((priceThreshold || sizeThreshold) && rateLimiter.isRequestAllowed()) {
                    // if size changed, check maxLong/ShortExposure
                    algorithm.cancelOrder(order, null);
                    logger.info("Canceled %s quoting order").with(side);
                }
            } else {
                if (side == Side.BUY) {
                    if (Decimal64Utils.isGreater(Decimal64Utils.add(currentPosition, openBuyQty, size), maxLongExposure) || !rateLimiter.isRequestAllowed())
                        continue;

                    openBuyQty = Decimal64Utils.add(openBuyQty, size);
                } else {
                    if (Decimal64Utils.isGreater(Decimal64Utils.add(Decimal64Utils.subtract(openSellQty, currentPosition), size), maxShortExposure) || !rateLimiter.isRequestAllowed())
                        continue;

                    openSellQty = Decimal64Utils.add(openSellQty, size);
                }
                orders[i] = algorithm.submitQuotingOrder(getSymbol(), size, price, exchange, side);
                logger.info("Submitted %s quoting order: %s @ %s").with(side).withDecimal64(size).withDecimal64(price);
            }
        }
    }

    private void removeFromActive(OutboundOrder order) {
        logger.info("PnL before removal: %s").withDecimal64(realizedPnL);
        logger.info("Position before removal: %s").withDecimal64(currentPosition);
        logger.info("OpenBuyQty before removal: %s").withDecimal64(openBuyQty);
        logger.info("OpenSellQty before removal: %s").withDecimal64(openSellQty);
        logger.info("OpenHedgeBuyQty before removal: %s").withDecimal64(openHedgeBuyQty);
        logger.info("OpenHedgeSellQty before removal: %s").withDecimal64(openHedgeSellQty);

        if (!order.getState().isFinal())
            throw new IllegalStateException("Order being removed is not final");

        @Decimal final long remainingQty = Decimal64Utils.subtract(order.getWorkingQuantity(), order.getTotalExecutedQuantity());
        final boolean isBuy = order.getSide() == Side.BUY;

        if (isBuy) {
            openBuyQty = Decimal64Utils.subtract(openBuyQty, remainingQty);
        } else {
            openSellQty = Decimal64Utils.subtract(openSellQty, remainingQty);
        }

        if (CharSequenceUtil.equals(order.getUserData(), "Hedger")) {
            activeHedgingOrders.remove(order);
            if (isBuy) {
                openHedgeBuyQty = Decimal64Utils.subtract(openHedgeBuyQty, remainingQty);
            } else {
                openHedgeSellQty = Decimal64Utils.subtract(openHedgeSellQty, remainingQty);
            }
            hedgerAction();
        } else {
            if (isBuy) {
                for (int i = 0; i < activeBuyOrders.length; i++) {
                    if (order.equals(activeBuyOrders[i])) {
                        activeBuyOrders[i] = null;
                    }
                }
            } else {
                for (int i = 0; i < activeSellOrders.length; i++) {
                    if (order.equals(activeSellOrders[i])) {
                        activeSellOrders[i] = null;
                    }
                }
            }
        }
    }

    private void hedgerAction() {
        logger.info("PnL before removal: %s").withDecimal64(realizedPnL);
        logger.info("Position before hedging: %s").withDecimal64(currentPosition);
        logger.info("OpenBuyQty before hedging: %s").withDecimal64(openBuyQty);
        logger.info("OpenSellQty before hedging: %s").withDecimal64(openSellQty);
        logger.info("OpenHedgeBuyQty before hedging: %s").withDecimal64(openHedgeBuyQty);
        logger.info("OpenHedgeSellQty before hedging: %s").withDecimal64(openHedgeSellQty);

        if (currentPosition == Decimal64Utils.ZERO && !activeHedgingOrders.isEmpty()) {
            // can be skipped if kept some variable of total pending cancel qty of hedging orders
            cancelHedgingOrders();
            return;
        }

        if (Decimal64Utils.isPositive(currentPosition)) {
            // first get rid of hedging buy quotes
            if (Decimal64Utils.isPositive(openHedgeBuyQty)) {
                cancelHedgingOrders();
                return;
            }

            // then verify that qty of hedging buy quotes <= current position
            // otherwise cancel excess
            // (this may be enhanced using some heuristics
            // e.g. cancel as precise total qty as possible
            // or cancel multiple orders at once)
            if (Decimal64Utils.isGreater(openHedgeSellQty, currentPosition)) {
                OutboundOrder o = activeHedgingOrders.get(0);
                if (!o.isCancelPending() && rateLimiter.isRequestAllowed())
                    algorithm.cancelOrder(o, null);
                return;
            }

            // check if we need to hedge
            if (Decimal64Utils.isLessOrEqual(currentPosition, positionMaxSize)) {
                return;
            }

            // can specify threshold in order to reduce rate
            // and max order size for some other reason
            @Decimal final long size = Decimal64Utils.subtract(Decimal64Utils.subtract(currentPosition, openHedgeSellQty), positionNormalSize);
            if (Decimal64Utils.isGreater(size, Decimal64Utils.ZERO) && rateLimiter.isRequestAllowed()) {
                @Decimal final long leanPrice = orderBook.getMarketSide(QuoteSide.BID).getBestQuote().getPrice();
                openHedgeSellQty = Decimal64Utils.add(openHedgeSellQty, size);
                openSellQty = Decimal64Utils.add(openSellQty, size);
                OutboundOrder hedgingOrder = algorithm.submitHedgingOrder(getSymbol(), size, leanPrice, sourceExchange, Side.SELL);
                activeHedgingOrders.add(hedgingOrder);
                logger.info("Submitted SELL hedging order: %s @ %s").withDecimal64(size).withDecimal64(leanPrice);
            }
        } else {
            if (Decimal64Utils.isPositive(openHedgeSellQty)) {
                cancelHedgingOrders();
                return;
            }

            @Decimal final long absCurrentPosition = Decimal64Utils.negate(currentPosition);
            if (Decimal64Utils.isGreater(openHedgeBuyQty, absCurrentPosition)) {
                OutboundOrder o = activeHedgingOrders.get(0);
                if (!o.isCancelPending() && rateLimiter.isRequestAllowed())
                    algorithm.cancelOrder(o, null);
                return;
            }

            if (Decimal64Utils.isLessOrEqual(absCurrentPosition, positionMaxSize)) {
                return;
            }

            @Decimal final long size = Decimal64Utils.subtract(Decimal64Utils.subtract(absCurrentPosition, openHedgeBuyQty), positionNormalSize);
            if (Decimal64Utils.isGreater(size, Decimal64Utils.ZERO) && rateLimiter.isRequestAllowed()) {
                @Decimal final long leanPrice = orderBook.getMarketSide(QuoteSide.ASK).getBestQuote().getPrice();
                openHedgeBuyQty = Decimal64Utils.add(openHedgeBuyQty, size);
                openBuyQty = Decimal64Utils.add(openBuyQty, size);
                OutboundOrder hedgingOrder = algorithm.submitHedgingOrder(getSymbol(), size, leanPrice, sourceExchange, Side.BUY);
                activeHedgingOrders.add(hedgingOrder);
                logger.info("Submitted BUY hedging order: %s @ %s").withDecimal64(size).withDecimal64(leanPrice);
            }
        }
    }

    private void cancelHedgingOrders() {
        for (int i = 0; i < activeHedgingOrders.size(); i++) {
            OutboundOrder o = activeHedgingOrders.get(i);
            if (!o.isCancelPending() && rateLimiter.isRequestAllowed())
                algorithm.cancelOrder(o, null);
        }
    }

    @Decimal
    private long getSize(int idx, Side side) {
        if (side == Side.BUY)
            return buyQuoteSizes[idx];
        else
            return sellQuoteSizes[idx];
    }

    @Decimal
    private long getPrice(int idx, Side side) {
        // validate that margins are ok with base price
        if (side == Side.BUY)
            return roundOrderPrice(Decimal64Utils.subtract(currentBidBasePrice, buyMargins[idx]), Side.BUY);
        else
            return roundOrderPrice(Decimal64Utils.add(currentAskBasePrice, sellMargins[idx]), Side.SELL);
    }

    @Decimal
    private long calculateBasePrice(QuoteSide side) {
        OrderBookQuote bestQuote = OrderBookHelper.getBestQuote(orderBook, sourceExchange, side);
        return (bestQuote != null) ? bestQuote.getPrice() : Decimal64Utils.NULL;
    }

    @Decimal
    private long roundOrderPrice(@Decimal long price, Side side) {
        return RoundingTools.roundPrice(price, side, getPriceIncrement());
    }

    @Decimal
    private long[] convertArrayFromDouble(final double[] arr) {
        final long[] result = new long[arr.length];
        for (int i = 0; i < arr.length; i++)
            result[i] = Decimal64Utils.fromDouble(arr[i]);
        return result;
    }

    private long getPriceIncrement() {
        return priceIncrement;
    }

    private void setPriceIncrement(long priceIncrement) {
        this.priceIncrement = priceIncrement;
    }

    protected void updateAllTimePosition(OrderTradeReportEvent tradeEvent, boolean isBuy) {
        // Position effect of the trade. Negative value indicates that position is decreasing
        @Decimal long positionEffect = tradeEvent.getTradeQuantity();
        if (!isBuy) {
            positionEffect = Decimal64Utils.negate(positionEffect);
        }
        final @Decimal long tradePrice = tradeEvent.getTradePrice();

        if (Decimal64Utils.isZero(currentPosition)) { // new position
            currentPosition = positionEffect;
            avgPositionPrice = tradePrice;

        } else if (Decimal64Utils.isPositive(currentPosition) == Decimal64Utils.isPositive(positionEffect)) { // increasing position (even if short one)
            @Decimal long positionCost = Decimal64Utils.add(
                    Decimal64Utils.multiply(currentPosition, avgPositionPrice),
                    Decimal64Utils.multiply(positionEffect, tradePrice));
            currentPosition = Decimal64Utils.add(currentPosition, positionEffect);
            avgPositionPrice = Decimal64Utils.divide(positionCost, currentPosition);

        } else if (Decimal64Utils.isLess(Decimal64Utils.abs(currentPosition), Decimal64Utils.abs(positionEffect))) { // reverse position
            // closing position: additional realizedPnL = closed_size * (cost_current - cost_paid)
            realizedPnL = Decimal64Utils.add(realizedPnL, getRealizedPnlEffect(currentPosition, avgPositionPrice, tradePrice));

            // opening reverse
            currentPosition = Decimal64Utils.add(currentPosition, positionEffect);
            avgPositionPrice = tradePrice;

        } else {  // decreasing position (maybe closing it)
            @Decimal long positionSizeClosed = Decimal64Utils.negate(positionEffect);
            realizedPnL = Decimal64Utils.add(realizedPnL, getRealizedPnlEffect(positionSizeClosed, avgPositionPrice, tradePrice));

            currentPosition = Decimal64Utils.add(currentPosition, positionEffect);
            if (Decimal64Utils.isZero(currentPosition))
                avgPositionPrice = Decimal64Utils.ZERO;
        }
    }

    /**
     * @return Realized PnL effect of closing position with specified size. Negative size indicates short position.
     */
    protected @Decimal long getRealizedPnlEffect(@Decimal long positionSize, @Decimal long entryPrice, @Decimal long exitPrice) {
        return Decimal64Utils.multiply(positionSize, Decimal64Utils.subtract(exitPrice, entryPrice));
    }

    private class RateLimiter {
        private static final long ONE_SECOND = 1000;
        private final int maxOrdersPerSecond;
        private final long[] orderTimestamps;
        private int head;

        RateLimiter(int maxOrdersPerSecond) {
            this.maxOrdersPerSecond = maxOrdersPerSecond;
            this.orderTimestamps = new long[this.maxOrdersPerSecond];
            this.head = 0;
        }

        /** this call modifies the state,
         * so this check should be the last one before submission
         *
         * @return true when we can submit an order
         */
        private boolean isRequestAllowed() {
            @Timestamp final long currentTime = algorithm.getTime(); // in ms
            if (orderTimestamps[head] != 0 && currentTime - orderTimestamps[head] < ONE_SECOND)
                return false; // can adapt to such limits, by means like resizing max number of quotes

            orderTimestamps[head] = currentTime;
            head = head + 1 == maxOrdersPerSecond ? 0 : head + 1;

            return true;
        }
    }
}