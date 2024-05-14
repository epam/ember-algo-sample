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
    private long currentPosition;
    @Decimal
    private long openBuyQty;
    @Decimal
    private long openSellQty;
    @Decimal
    private long openHedgeBuyQty; // buyQty and SellQty both cannot be != 0
    @Decimal
    private long openHedgeSellQty;
    @Decimal
    private long priceIncrement;
    @Decimal
    private long currentAskBasePrice;
    @Decimal
    private long currentBidBasePrice;

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
    private final long[] sellMargins; // TODO: probably, round to tick, and basePrice also to avoid rounding operations later, but can lose precision
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
        this.currentPosition = Decimal64Utils.ZERO;
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


        if (!isSubscribed || !algorithm.isLeaderNode())
            return;

        currentAskBasePrice = calculateBasePrice(QuoteSide.ASK);
        currentBidBasePrice = calculateBasePrice(QuoteSide.BID);
        if (currentBidBasePrice != Decimal64Utils.NULL && currentAskBasePrice != Decimal64Utils.NULL) {
            assert Decimal64Utils.isGreater(currentAskBasePrice, currentBidBasePrice);
            @Decimal long adjustment = Decimal64Utils.subtract(minSpread, Decimal64Utils.subtract(currentAskBasePrice, currentBidBasePrice));
            if (Decimal64Utils.isPositive(adjustment)) {
                // one more extra parameter being added overrides the default % attribution (50/50 to buy and sell quote arrays) for spread increase.
                // see https://kb.marketmaker.cloud/market-maker-application/trading/bot-configuration#min-spread-examples
                @Decimal long halfAdjustment = Decimal64Utils.divide(adjustment, 2);
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
            currentPosition = Decimal64Utils.add(currentPosition, tradeQty);
            if (isHedger)
                openHedgeBuyQty = Decimal64Utils.subtract(openHedgeBuyQty, tradeQty);
            openBuyQty = Decimal64Utils.subtract(openBuyQty, tradeQty);
        } else {
            currentPosition = Decimal64Utils.subtract(currentPosition, tradeQty);
            if (isHedger)
                openHedgeSellQty = Decimal64Utils.subtract(openHedgeSellQty, tradeQty);
            openSellQty = Decimal64Utils.subtract(openSellQty, tradeQty);
        }

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

    public void onLeaderState(OutboundOrder order) {
    }

    private void processOrders(Side side) {
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
                if ((priceThreshold || sizeThreshold) && rateLimiter.isOrderAllowed()) {
                    // if size changed, check maxLong/ShortExposure
                    algorithm.cancelOrder(order);
                    logger.info("Canceled %s quoting order: %s").with(side).with(order);
                }
            } else {
                if (side == Side.BUY) {
                    if (Decimal64Utils.isGreater(Decimal64Utils.add(currentPosition, openBuyQty, size), maxLongExposure) || !rateLimiter.isOrderAllowed())
                        continue;

                    // todo: waiting for event will make this slower
                    //  so we hope that eventually order will be accepted
                    openBuyQty = Decimal64Utils.add(openBuyQty, size);
                } else {
                    if (Decimal64Utils.isGreater(Decimal64Utils.add(Decimal64Utils.subtract(openSellQty, currentPosition), size), maxShortExposure) || !rateLimiter.isOrderAllowed())
                        continue;

                    openSellQty = Decimal64Utils.add(openSellQty, size);
                }
                orders[i] = algorithm.submitQuotingOrder(getSymbol(), size, price, exchange, side);
                logger.info("Submitted %s quoting order: %s").with(side).with(orders[i]);
            }
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

    private void removeFromActive(OutboundOrder order) {
        System.out.println(Decimal64Utils.toString(currentPosition));
        System.out.println(Decimal64Utils.toString(openBuyQty));
        System.out.println(Decimal64Utils.toString(openSellQty));
        System.out.println(Decimal64Utils.toString(openHedgeBuyQty));
        System.out.println(Decimal64Utils.toString(openHedgeSellQty));
        System.out.println();

        if (!order.getState().isFinal())
            throw new IllegalStateException("Order being removed is not final");

        @Decimal final long remainingQty = Decimal64Utils.subtract(order.getWorkingQuantity(), order.getTotalExecutedQuantity());
        boolean isBuy = order.getSide() == Side.BUY;

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
        if (currentPosition == Decimal64Utils.ZERO && !activeHedgingOrders.isEmpty() && rateLimiter.isOrderAllowed()) {
            // it may be better to remove everything at once
            OutboundOrder o = activeHedgingOrders.get(activeHedgingOrders.size() - 1);
            algorithm.cancelOrder(o);
            return;
        }

        if (Decimal64Utils.isPositive(currentPosition)) {
            // first get rid of hedging buy quotes
            if (Decimal64Utils.isPositive(openHedgeBuyQty) && rateLimiter.isOrderAllowed()) {
                algorithm.cancelOrder(activeHedgingOrders.get(activeHedgingOrders.size() - 1));
                return;
            }

            // then verify that qty of hedging buy quotes <= current position
            // otherwise cancel excess
            // this maybe enhanced using some heuristics
            // e.g. try removing as few orders as possible using knapsack in order to reduce rate
            if (Decimal64Utils.isGreater(openHedgeSellQty, currentPosition) && rateLimiter.isOrderAllowed()) {
                algorithm.cancelOrder(activeHedgingOrders.get(activeHedgingOrders.size() - 1));
                return;
            }

            // check if we need to hedge
            if (Decimal64Utils.isLessOrEqual(currentPosition, positionMaxSize)) {
                return;
            }

            // can specify threshold in order to reduce rate
            // and max order size for some reason
            @Decimal final long size = Decimal64Utils.subtract(Decimal64Utils.subtract(currentPosition, openHedgeSellQty), positionNormalSize);
            if (Decimal64Utils.isGreater(size, Decimal64Utils.ZERO) && rateLimiter.isOrderAllowed()) {
                @Decimal final long leanPrice = orderBook.getMarketSide(QuoteSide.BID).getBestQuote().getPrice();
                openHedgeSellQty = Decimal64Utils.add(openHedgeSellQty, size);
                openSellQty = Decimal64Utils.add(openSellQty, size);
                OutboundOrder hedgingOrder = algorithm.submitHedgingOrder(getSymbol(), size, leanPrice, sourceExchange, Side.SELL);
                activeHedgingOrders.add(hedgingOrder);
                logger.info("Submitted %s hedging order: %s").with(Side.SELL).with(hedgingOrder);
            }
        } else {
            if (Decimal64Utils.isPositive(openHedgeSellQty) && rateLimiter.isOrderAllowed()) {
                algorithm.cancelOrder(activeHedgingOrders.get(activeHedgingOrders.size() - 1));
                return;
            }

            @Decimal final long absCurrentPosition = Decimal64Utils.negate(currentPosition);
            if (Decimal64Utils.isGreater(openHedgeBuyQty, absCurrentPosition) && rateLimiter.isOrderAllowed()) {
                algorithm.cancelOrder(activeHedgingOrders.get(activeHedgingOrders.size() - 1));
                return;
            }

            if (Decimal64Utils.isLessOrEqual(absCurrentPosition, positionMaxSize)) {
                return;
            }

            @Decimal final long size = Decimal64Utils.subtract(Decimal64Utils.subtract(absCurrentPosition, openHedgeBuyQty), positionNormalSize);
            if (Decimal64Utils.isGreater(size, Decimal64Utils.ZERO) && rateLimiter.isOrderAllowed()) {
                @Decimal final long leanPrice = orderBook.getMarketSide(QuoteSide.ASK).getBestQuote().getPrice();
                openHedgeBuyQty = Decimal64Utils.add(openHedgeBuyQty, size);
                openBuyQty = Decimal64Utils.add(openBuyQty, size);
                OutboundOrder hedgingOrder = algorithm.submitHedgingOrder(getSymbol(), size, leanPrice, sourceExchange, Side.BUY);
                activeHedgingOrders.add(hedgingOrder);
                logger.info("Submitted %s hedging order: %s").with(Side.BUY).with(hedgingOrder);
            }
        }
    }

    private long getPriceIncrement() {
        return priceIncrement;
    }

    private void setPriceIncrement(long priceIncrement) {
        this.priceIncrement = priceIncrement;
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
        private boolean isOrderAllowed() {
            @Timestamp final long currentTime = algorithm.getTime(); // in ms
            if (orderTimestamps[head] != 0 && currentTime - orderTimestamps[head] < ONE_SECOND)
                return false; // can adapt to such limits, by means like resizing max number of quotes

            orderTimestamps[head] = currentTime;
            head = head + 1 == maxOrdersPerSecond ? 0 : head + 1;

            return true;
        }
    }
}
