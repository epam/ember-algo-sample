package deltix.ember.samples.algorithm.tor;

import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.ember.message.smd.InstrumentType;
import deltix.ember.message.trade.Side;
import deltix.ember.service.algorithm.md.AbstractInstrumentData;
import deltix.qsrv.hf.pub.InstrumentMessage;
import deltix.quoteflow.orderbook.interfaces.OrderBookLevel;
import deltix.quoteflow.orderbook.interfaces.OrderBookList;
import deltix.quoteflow.orderbook.interfaces.OrderBookQuote;
import deltix.timebase.api.messages.MarketMessageInfo;
import deltix.timebase.api.messages.QuoteSide;
import rtmath.containers.BufferedLinkedList;

public class TORInstrumentData extends AbstractInstrumentData {

    private final BufferedLinkedList<OrderBookQuote> sortedQuotes = new BufferedLinkedList<>();
    private TORAlgorithm algorithm;
    private TOROrderBook orderBook;

    public TORInstrumentData(CharSequence symbol, InstrumentType instrumentType, TORAlgorithm algorithm) {
        super(symbol, instrumentType);
        this.algorithm = algorithm;
        this.orderBook = new TOROrderBook(getSymbol());
    }


    @Override
    public void onMarketMessage(InstrumentMessage message) {
        if (message instanceof MarketMessageInfo)
            orderBook.update((MarketMessageInfo) message);
    }

    void onChildOrderAdded(TORChildOrder childOrder) {
        orderBook.reserveQuote(childOrder);
    }

    void onChildOrderRemoved(TORChildOrder childOrder) {
        orderBook.unreserveQuote(childOrder);
    }

    // penalize LP by excluding the quote until it is refreshed
    void onOrderRejected(TORChildOrder childOrder) {
        orderBook.excludeQuote(childOrder);
    }

    void match(TOROrder order) {
        order.setInstrumentData(this);

        OrderBookList<OrderBookLevel> quoteLevels = orderBook.getAllLevels(oppositeQuoteSide(order.getSide()));
        int levelIndex = 0;
        OrderBookLevel quoteLevel = quoteLevels.size() > levelIndex ? quoteLevels.getObjectAt(levelIndex++) : null;

        @Decimal long takerQuantity = order.getAvailableQuantity();

        while (quoteLevel != null && Decimal64Utils.isPositive(takerQuantity) &&
                order.isAcceptablePrice(quoteLevel.getPrice())) {

            @Decimal long tradePrice = quoteLevel.getPrice();

            BufferedLinkedList<OrderBookQuote> quotes = getSortedQuotes(quoteLevel.getQuotes());
            for (int key = quotes.getFirstKey(); key >= 0; key = quotes.next(key)) {
                OrderBookQuote quote = quotes.getElementByKey(key);
                long exchangeId = quote.getExchangeId();

                @Decimal long tradeQuantity = roundOrderQuantity(takerQuantity, exchangeId, getSymbol());

                // cancel order if taker quantity is less than min order size and it has no active children
                if (!Decimal64Utils.isPositive(tradeQuantity)) {
                    if (!order.hasActiveChildren()) {
                        algorithm.cancelAlgoOrder(order, "Remaining quantity less than minimum order size");
                    }
                    return;
                }

                // skip quotes that are targeted by other orders and have too little liquidity available
                long availableQuoteQuantity = roundOrderQuantity(orderBook.getAvailableQuantity(quote), exchangeId, getSymbol());
                if (!Decimal64Utils.isPositive(availableQuoteQuantity)) {
                    continue;
                }

                tradeQuantity = Decimal64Utils.min(tradeQuantity, availableQuoteQuantity);
                CharSequence quoteId = orderBook.getQuoteId(quote);
                algorithm.submitChildOrder(order, quote.getExchangeId(), quoteId, quote.getPrice(), tradeQuantity);

                takerQuantity = Decimal64Utils.subtract(takerQuantity, tradeQuantity);
                if (!Decimal64Utils.isPositive(takerQuantity))
                    return;
           }

            quoteLevel = quoteLevels.size() > levelIndex ? quoteLevels.getObjectAt(levelIndex++) : null;
        }
    }

    private BufferedLinkedList<OrderBookQuote> getSortedQuotes
    (BufferedLinkedList < OrderBookQuote > quotes) {
        sortedQuotes.clear();
        for (int key = quotes.getFirstKey(); key >= 0; key = quotes.next(key)) {
            insertQuote(sortedQuotes, quotes.getElementByKey(key));
        }
        return sortedQuotes;
    }

    private @Decimal long roundOrderQuantity(@Decimal long quantity, long exchangeId, CharSequence symbol) {
        //TODO obtain precision and min order size for exchange and instrument
        @Decimal long orderSizePrecision = Decimal64Utils.fromDouble(0.001);
        @Decimal long minOrderSize = Decimal64Utils.ZERO;

        if (!Decimal64Utils.isNaN(orderSizePrecision)) {
            quantity = roundOrderQuantity(quantity, orderSizePrecision);
        }
        if (Decimal64Utils.isPositive(minOrderSize) && Decimal64Utils.isLess(quantity, minOrderSize)) {
            quantity = Decimal64Utils.ZERO;
        }
        return quantity;
    }

    private static @Decimal long roundOrderQuantity(final @Decimal long quantity, final @Decimal long orderSizePrecision) {
        // round quantity down, because we may exceed existing limitations (balance for example)
        if (Decimal64Utils.isNull(orderSizePrecision))
            return quantity;
        return Decimal64Utils.max(Decimal64Utils.ZERO, Decimal64Utils.roundTowardsNegativeInfinity(quantity, orderSizePrecision));
    }

    private static void insertQuote(BufferedLinkedList<OrderBookQuote> quotes, OrderBookQuote newQuote) {
        @Decimal long newQuoteSize = newQuote.getSize();
        for (int key = quotes.getFirstKey(); key >= 0; key = quotes.next(key)) {
            OrderBookQuote quote = quotes.getElementByKey(key);
            int result = Decimal64Utils.compareTo(newQuoteSize, quote.getSize());
            if (result > 0 || (result == 0 && newQuote.getTime() < quote.getTime())) {
                quotes.addBefore(key, newQuote);
                return;
            }
        }
        quotes.addLast(newQuote);
    }

    private static Side oppositeSide(Side side) {
        return side == Side.BUY ? Side.SELL : Side.BUY;
    }

    private static QuoteSide oppositeQuoteSide(Side side) {
        return side == Side.BUY ? QuoteSide.ASK : QuoteSide.BID;
    }
}
