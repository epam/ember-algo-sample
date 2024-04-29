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
    private static final int RATE_LIMIT = 100; // per second
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
    private boolean isAboveMaxLongExposure;
    private boolean isBelowMaxShortExposure;
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
    private final long minBuyQuoteActiveTime;
    private final long minSellQuoteActiveTime;
    @Decimal
    private final long maxQuoterPositionSize;
    @Decimal
    private final long maxHedgerPositionSize;

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
        minSellQuoteActiveTime = settings.getMinSellQuoteActiveTime();
        minBuyQuoteActiveTime = settings.getMinBuyQuoteActiveTime();
        maxQuoterPositionSize = Decimal64Utils.fromDouble(settings.getMaxQuoterPositionSize());
        maxHedgerPositionSize = Decimal64Utils.fromDouble(settings.getMaxHedgerPositionSize());

        // hedger
        positionNormalSize = Decimal64Utils.fromDouble(settings.getPositionNormalSize());
        positionMaxSize = Decimal64Utils.fromDouble(settings.getPositionMaxSize());

        this.algorithm = algorithm;
        this.logger = logger;
        this.currentPosition = Decimal64Utils.ZERO;
        this.activeSellOrders = new OutboundOrder[sellQuoteSizes.length];
        this.activeBuyOrders = new OutboundOrder[buyQuoteSizes.length];
        this.activeHedgingOrders = new ArrayList<>();
        this.rateLimiter = new RateLimiter(RATE_LIMIT);
        isSubscribed = algorithm.isSubscribed(getSymbol());
        isAboveMaxLongExposure = false;
        isBelowMaxShortExposure = false;
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
            processOrders(QuoteSide.ASK);
            processOrders(QuoteSide.BID);
        }
    }

    public void onFilled(OutboundOrder order, OrderTradeReportEvent event) {
        boolean isBuy = order.getSide() == Side.BUY;
        final boolean isHedger = CharSequenceUtil.equals(order.getUserData(), "Hedger");
        @Decimal final long tradeQty = event.getTradeQuantity();
        @Decimal final long totalQty = order.getWorkingQuantity();
        if (isBuy) {
            currentPosition = Decimal64Utils.add(currentPosition, tradeQty);
            if (isHedger) {
                openHedgeBuyQty = Decimal64Utils.subtract(openHedgeBuyQty, totalQty); // if ok whole size should be subtracted
                openBuyQty = Decimal64Utils.subtract(openBuyQty, totalQty);

                if (isAboveMaxLongExposure && Decimal64Utils.isLessOrEqual(Decimal64Utils.add(currentPosition, openBuyQty), maxLongExposure))
                    isAboveMaxLongExposure = false;
            } else {
                openBuyQty = Decimal64Utils.subtract(openBuyQty, tradeQty);
            }

        } else {
            currentPosition = Decimal64Utils.subtract(currentPosition, tradeQty);
            if (isHedger) {
                openHedgeSellQty = Decimal64Utils.subtract(openHedgeSellQty, totalQty);
                openSellQty = Decimal64Utils.subtract(openSellQty, totalQty);

                if (isBelowMaxShortExposure && Decimal64Utils.isLessOrEqual(Decimal64Utils.subtract(openSellQty, currentPosition), maxShortExposure))
                    isBelowMaxShortExposure = false;
            } else {
                openSellQty = Decimal64Utils.subtract(openSellQty, tradeQty);
            }
        }

        if (!isHedger)
            hedgerAction();
    }

    public void onRejected(OutboundOrder order, OrderRejectEvent event) {
        // TODO: logic depends on reject reason
        // now it assumes SAFE REJECT
        removeFromActive(order);
        resurrectOrder(order);
    }

    public void onCanceled(OutboundOrder order, OrderCancelEvent event) {
        // safe cancel
        removeFromActive(order);
        resurrectOrder(order);
    }

    public void onNew(OutboundOrder order, OrderNewEvent event) {
    }

    public void onLeaderState(OutboundOrder order) {
//        if (order.getSide() == Side.BUY)
//            entryOrder = order;
//        else
//            exitOrder = order;
    }

    private void processOrders(QuoteSide side) {
        OutboundOrder[] orders = (side == QuoteSide.BID) ? activeBuyOrders : activeSellOrders;

        for (int i = 0; i < ((side == QuoteSide.BID) ? buyQuoteSizes : sellQuoteSizes).length; i++) {
            if (!rateLimiter.isOrderAllowed())
                return;

            @Decimal final long size = getSize(i, side);
            @Decimal final long price = getPrice(i, side);

            OutboundOrder order = orders[i];
            if (order != null) {
                // check if we really need to replace via min price/size change
                boolean priceThreshold = Decimal64Utils.isGreater(Decimal64Utils.abs(Decimal64Utils.subtract(order.getWorkingOrder().getLimitPrice(), price)), minPriceChange);
                // size is probably only useful in REPLICATION source aggregation method
                boolean sizeThreshold = false;
                if (priceThreshold || sizeThreshold) {
                    // if size changed, check maxLong/ShortExposure
                    algorithm.cancelOrder(order);
                }
            } else {
                if (side == QuoteSide.BID) {
                    if (isAboveMaxLongExposure)
                        break;

                    openBuyQty = Decimal64Utils.add(openBuyQty, size); // todo: waiting for event will make every orderRequest submitted
                    if (Decimal64Utils.isGreater(Decimal64Utils.add(currentPosition, openBuyQty), maxLongExposure))
                        isAboveMaxLongExposure = true;

                    orders[i] = algorithm.submitQuotingOrder(getSymbol(), size, price, exchange, Side.BUY);
                } else {
                    if (isBelowMaxShortExposure)
                        break;

                    openSellQty = Decimal64Utils.add(openSellQty, size);
                    if (Decimal64Utils.isGreater(Decimal64Utils.subtract(openSellQty, currentPosition), maxShortExposure))
                        isBelowMaxShortExposure = true;

                    orders[i] = algorithm.submitQuotingOrder(getSymbol(), size, price, exchange, Side.SELL);
                }
                logger.info("Submitted %s quoting order: %s").with(side).with(orders[i]);
            }
        }
    }

    @Decimal
    private long getSize(int idx, QuoteSide side) {
        if (side == QuoteSide.BID)
            return buyQuoteSizes[idx];
        else
            return sellQuoteSizes[idx];
    }

    @Decimal
    private long getPrice(int idx, QuoteSide side) {
        if (side == QuoteSide.BID)
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
        @Decimal final long remainingQty = order.getRemainingQuantity();

        if (CharSequenceUtil.equals(order.getUserData(), "Hedger")) {
            activeHedgingOrders.remove(order);
            if (order.getSide() == Side.BUY) {
                openBuyQty = Decimal64Utils.subtract(openBuyQty, remainingQty);
                openHedgeBuyQty = Decimal64Utils.subtract(openHedgeBuyQty, remainingQty);
            } else {
                openSellQty = Decimal64Utils.subtract(openSellQty, remainingQty);
                openHedgeSellQty = Decimal64Utils.subtract(openHedgeSellQty, remainingQty);
            }
        } else {
            if (order.getSide() == Side.SELL)
                openSellQty = Decimal64Utils.subtract(openSellQty, remainingQty);
            else
                openBuyQty = Decimal64Utils.subtract(openBuyQty, remainingQty);
        }
    }

    private void resurrectOrder(OutboundOrder order) {
        assert order.getState().isFinal();
        final boolean isHedger = CharSequenceUtil.equals(order.getUserData(), "Hedger");
        if (isHedger) {
            hedgerAction();
        } else {
            Side side = order.getSide();
            if (side == Side.BUY) {
                QuoteSide quoteSide = QuoteSide.BID;
                int i = 0;
                for (; i < activeBuyOrders.length; i++) {
                    if (order.equals(activeBuyOrders[i])) {
                        break;
                    }
                }

                if (rateLimiter.isOrderAllowed()) {
                    order = algorithm.submitQuotingOrder(getSymbol(), getSize(i, quoteSide), getPrice(i, quoteSide), exchange, side);
                    activeBuyOrders[i] = order;
                    logger.info("Replaced %s quoting order: %s").with(side).with(order);
                } else
                    activeBuyOrders[i] = null;
                // will not be resurrected till next onMarketUpdate()
                // probably we should call processOrders()
                // it will try to set most competitive ASKs and BIDs instead of this particular order
                // todo: is it possible that quote from middle get filled earlier than best bid or ask?

            } else {
                QuoteSide quoteSide = QuoteSide.ASK;
                int i = 0;
                for (; i < activeSellOrders .length; i++) {
                    if (order.equals(activeSellOrders[i])) {
                        break;
                    }
                }

                if (rateLimiter.isOrderAllowed()) {
                    order = algorithm.submitQuotingOrder(getSymbol(), getSize(i, quoteSide), getPrice(i, quoteSide), exchange, side);
                    activeSellOrders[i] = order;
                    logger.info("Replaced %s quoting order: %s").with(side).with(order);
                } else
                    activeSellOrders[i] = null;
            }
        }
    }

    private void hedgerAction() {
        if (!rateLimiter.isOrderAllowed())
            return;

        System.out.println(Decimal64Utils.toString(currentPosition));
        System.out.println(Decimal64Utils.toString(openBuyQty));
        System.out.println(Decimal64Utils.toString(openSellQty));
        System.out.println(Decimal64Utils.toString(openHedgeBuyQty));
        System.out.println(Decimal64Utils.toString(openHedgeSellQty));
        System.out.println();

        // take active hedging orders into account
        @Decimal final long currentPositionCorrection = Decimal64Utils.add(currentPosition, Decimal64Utils.subtract(openHedgeBuyQty, openHedgeSellQty));
        @Decimal final long absCurrentPositionCorrection = Decimal64Utils.abs(currentPositionCorrection);

        if (Decimal64Utils.isGreater(absCurrentPositionCorrection, positionMaxSize)) {
            // TODO: add Max Order Size
            @Decimal final long size = Decimal64Utils.subtract(absCurrentPositionCorrection, positionNormalSize);
            if (Decimal64Utils.isPositive(currentPositionCorrection)) {
                @Decimal final long leanPrice = orderBook.getMarketSide(QuoteSide.BID).getBestQuote().getPrice();
                openHedgeSellQty = Decimal64Utils.add(openHedgeSellQty, size);
                openSellQty = Decimal64Utils.add(openSellQty, size);
                OutboundOrder hedgingOrder = algorithm.submitHedgingOrder(getSymbol(), size, leanPrice, sourceExchange, Side.SELL);
                activeHedgingOrders.add(hedgingOrder);
                logger.info("Submitted %s hedging order: %s").with(Side.SELL).with(hedgingOrder);
            } else {
                @Decimal final long leanPrice = orderBook.getMarketSide(QuoteSide.ASK).getBestQuote().getPrice();
                openHedgeBuyQty = Decimal64Utils.add(openHedgeBuyQty, size);
                openBuyQty = Decimal64Utils.add(openBuyQty, size);
                OutboundOrder hedgingOrder = algorithm.submitHedgingOrder(getSymbol(), size, leanPrice, sourceExchange, Side.BUY);
                activeHedgingOrders.add(hedgingOrder);
                logger.info("Submitted %s hedging order: %s").with(Side.BUY).with(hedgingOrder);
            }
        }

        if (Decimal64Utils.isLessOrEqual(absCurrentPositionCorrection, positionNormalSize)) {
            for (int i = 0; i < activeHedgingOrders.size(); i++)
                algorithm.cancelOrder(activeHedgingOrders.get(i));

            activeHedgingOrders.clear();
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

        private boolean isOrderAllowed() {
            @Timestamp final long currentTime = algorithm.getTime(); // in ms
            if (orderTimestamps[head] != 0 && currentTime - orderTimestamps[head] < ONE_SECOND)
                return false; // todo: adapt to such limits, like resizing max number of quotes

            orderTimestamps[head] = currentTime;
            head = head + 1 == maxOrdersPerSecond ? 0 : head + 1;

            return true;
        }
    }
}
