package deltix.ember.samples.algorithm.tor;

import deltix.anvil.util.ObjectPool;
import deltix.anvil.util.annotation.Alphanumeric;
import deltix.anvil.util.codec.AlphanumericCodec;
import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.ember.message.trade.Side;
import deltix.quoteflow.orderbook.FullOrderBook;
import deltix.quoteflow.orderbook.interfaces.DisconnectBehaviour;
import deltix.quoteflow.orderbook.interfaces.L2QuoteIdMode;
import deltix.quoteflow.orderbook.interfaces.OrderBookQuote;
import deltix.timebase.api.messages.DataModelType;
import deltix.timebase.api.messages.MarketMessageInfo;
import deltix.timebase.api.messages.QuoteSide;
import deltix.timebase.api.messages.universal.*;
import deltix.util.collections.generated.ObjectArrayList;
import deltix.util.collections.generated.ObjectToObjectHashMap;
import rtmath.containers.BinaryAsciiString;
import rtmath.containers.BufferedLinkedList;

import java.util.function.Consumer;

import static deltix.quoteflow.orderbook.OrderBookWaitingSnapshotMode.NOT_WAITING_FOR_SNAPSHOT;


public class TOROrderBook extends FullOrderBook {

    private static final ObjectPool<QuoteInfo> objectPool = new ObjectPool<>(20, QuoteInfo::new);

    private final BufferedLinkedList<ExchangeQuotes> exchangeQuotes = new BufferedLinkedList<>();

    public TOROrderBook(CharSequence symbol) {
        super(symbol, NOT_WAITING_FOR_SNAPSHOT, null, DataModelType.LEVEL_TWO);

        this.setDisconnectBehaviour(DisconnectBehaviour.CLEAR_EXCHANGE);
        this.setL2QuoteIdMode(L2QuoteIdMode.NO_REWRITE_IF_EXIST);
    }

    @Override
    public void update(MarketMessageInfo message) {
        preprocess(message);
        super.update(message);

        // check if any excluded quotes need to be cleared
        for (int key = exchangeQuotes.getFirstKey(); key >= 0; key = exchangeQuotes.next(key)) {
            exchangeQuotes.getElementByKey(key).checkExcluded();
        }
    }

    void excludeQuote(TORChildOrder childOrder) {
        long exchangeId = childOrder.getExchangeId();
        OrderBookQuote quote = getQuoteById(getTargetQuoteId(childOrder), exchangeId);
        if (quote != null)
            getExchangeQuotes(exchangeId).markExcluded(quote);
    }

    void reserveQuote(TORChildOrder childOrder) {
        OrderBookQuote quote = getQuoteById(getTargetQuoteId(childOrder), childOrder.getExchangeId());
        if (quote != null)
            getExchangeQuotes(childOrder.getExchangeId()).reserveQuote(quote, childOrder);
    }

    void unreserveQuote(TORChildOrder childOrder) {
        getExchangeQuotes(childOrder.getExchangeId()).unreserveQuote(childOrder);
    }

    @Decimal
    long getAvailableQuantity(OrderBookQuote quote) {
        return getExchangeQuotes(quote.getExchangeId()).getAvailableQuantity(quote);
    }

    private ExchangeQuotes getExchangeQuotes(long exchangeId) {
        ExchangeQuotes quotes;
        for (int key = exchangeQuotes.getFirstKey(); key >= 0; key = exchangeQuotes.next(key)) {
            quotes = exchangeQuotes.getElementByKey(key);
            if (quotes.exchangeId == exchangeId)
                return quotes;
        }
        quotes = new ExchangeQuotes(exchangeId);
        exchangeQuotes.addFirst(quotes);
        return quotes;
    }

    class ExchangeQuotes {
        private long exchangeId;
        private ObjectToObjectHashMap<CharSequence, QuoteInfo> reservedQuotes = new ObjectToObjectHashMap<>();
        private Consumer<QuoteInfo> quoteInfoHandler = this::checkQuote;

        private ExchangeQuotes(long exchangeId) {
            this.exchangeId = exchangeId;
        }

        private QuoteInfo getOrCreate(OrderBookQuote quote) {
            QuoteInfo reservation = reservedQuotes.get(quote.getQuoteId(), null);
            if (reservation == null) {
                reservation = objectPool.borrow();
                reservation.init(quote);
                reservedQuotes.put(reservation.quoteId, reservation);
            }
            return reservation;
        }

        private void removeQuoteInfo(QuoteInfo quoteInfo) {
            reservedQuotes.remove(quoteInfo.quoteId);
            quoteInfo.clear();
            objectPool.release(quoteInfo);
        }

        void reserveQuote(OrderBookQuote quote, TORChildOrder child) {
            QuoteInfo reservation = getOrCreate(quote);
            reservation.update(quote);
            reservation.reservedQuantity = Decimal64Utils.add(reservation.reservedQuantity, child.getWorkingQuantity());
        }

        void unreserveQuote(TORChildOrder child) {
            CharSequence quoteId = getTargetQuoteId(child);
            QuoteInfo reservation = reservedQuotes.get(quoteId, null);
            if (reservation != null) {
                reservation.reservedQuantity = Decimal64Utils.subtract(reservation.reservedQuantity, child.getWorkingQuantity());
                if (!reservation.excluded && !Decimal64Utils.isPositive(reservation.reservedQuantity)) {
                    removeQuoteInfo(reservation);
                }
            }
        }

        void markExcluded(OrderBookQuote quote) {
            QuoteInfo quoteInfo = getOrCreate(quote);
            if (quoteInfo.lastUpdated < quote.getLastUpdateTime()) {
                // order book quote was updated since last reservation so no need to exclude it anymore
                quoteInfo.update(quote);
                if (!quoteInfo.excluded && !Decimal64Utils.isPositive(quoteInfo.reservedQuantity)) {
                    removeQuoteInfo(quoteInfo);
                }
            }
            else {
                quoteInfo.excluded = true;
            }
        }

        void checkExcluded() {
            reservedQuotes.forEach(quoteInfoHandler);
        }

        private void checkQuote(QuoteInfo quoteInfo) {
            if (quoteInfo.excluded) {
                OrderBookQuote quote = getQuoteById(quoteInfo.quoteId, exchangeId);
                if (quote != null && quote.getLastUpdateTime() > quoteInfo.lastUpdated) {
                    quoteInfo.update(quote);
                    quoteInfo.excluded = false;
                }
                // if corresponding quote does not exist anymore or quote info has no additional data it can be removed
                if (quote == null || (!quoteInfo.excluded && !Decimal64Utils.isPositive(quoteInfo.reservedQuantity))) {
                    removeQuoteInfo(quoteInfo);
                }
            }
        }

        @Decimal long getAvailableQuantity(OrderBookQuote quote) {
            QuoteInfo reservation = reservedQuotes.get(quote.getQuoteId(), null);
            if (reservation == null)
                return quote.getSize();

            if (reservation.excluded)
                return Decimal64Utils.ZERO;

            @Decimal long quantity = Decimal64Utils.subtract(quote.getSize(), reservation.reservedQuantity);
            return Decimal64Utils.isPositive(quantity) ? quantity : Decimal64Utils.ZERO;
        }
    }

    static class QuoteInfo {
        private BinaryAsciiString quoteId = new BinaryAsciiString();
        private long lastUpdated = 0;
        private @Decimal long reservedQuantity = Decimal64Utils.ZERO;
        private boolean excluded = false;

        void init(OrderBookQuote quote) {
            quoteId.clear();
            quoteId.append(quote.getQuoteId());
            update(quote);
        }

        void update(OrderBookQuote quote) {
            assert quote.getQuoteId().equals(quoteId);
            lastUpdated = quote.getLastUpdateTime();
        }

        void clear() {
            quoteId.clear();
            reservedQuantity = Decimal64Utils.ZERO;
            lastUpdated = 0;
            excluded = false;
        }
    }


    private static final @Alphanumeric long XBTO_EXCHANGE_ID = AlphanumericCodec.encode("XBTO");

    // XBTO generates order book where bid and ask entries use the same QuoteID which breaks FullOrderBook logic.
    // Temporary? solution - rewrite XBTO feed: append market side to each quote ID to make it unique.
    // https://gitlab.deltixhub.com/Clients/TradeStation/Algorithms/issues/15
    private static MarketMessageInfo preprocess(MarketMessageInfo message) {
        if (message instanceof PackageHeader) {
            ObjectArrayList<BaseEntryInfo> entries = ((PackageHeader) message).getEntries();
            if (entries != null) {
                for (int i=0; i < entries.size(); i++) {
                    BaseEntryInfo entry = entries.get(i);
                    if (entry.getExchangeId() != XBTO_EXCHANGE_ID)
                        break;
                    if (entry instanceof L2EntryNew) {
                        L2EntryNew priceEntry = (L2EntryNew) entry;
                        if (priceEntry.getQuoteId() instanceof StringBuilder) { //TODO: confirm with Alex K
                            StringBuilder sb = (StringBuilder) priceEntry.getQuoteId();
                            sb.append(':').append(priceEntry.getSide().name());
                        }
                    } else
                    if (entry instanceof L2EntryUpdate) {
                        L2EntryUpdate priceEntry = (L2EntryUpdate) entry;
                        if (priceEntry.getQuoteId() instanceof StringBuilder) { //TODO: confirm with Alex K
                            StringBuilder sb = (StringBuilder) priceEntry.getQuoteId();
                            sb.append(':').append(priceEntry.getSide().name());
                        }
                    } else
                        // Temporary patch for https://gitlab.deltixhub.com/Deltix/Common/QuoteFlow/issues/131
                        if (entry instanceof BookResetEntry) {
                            // move to the beginning
                            entries.remove(i);
                            entries.add(0, entry);
                        }
                }
            }
        }
        return message;
    }

    CharSequence getQuoteId(OrderBookQuote quote) {
        if (quote.getExchangeId() != XBTO_EXCHANGE_ID)
            return quote.getQuoteId();

        BinaryAsciiString quoteId = quote.getQuoteId();
        String side = quote.getQuoteSide().name();
        int separatorIndex = quoteId.length() - side.length() - 1;
        if (separatorIndex >= 0 && quoteId.charAt(separatorIndex) == ':') {
            BinaryAsciiString str = new BinaryAsciiString();
            for (int i = 0; i < separatorIndex; ++i) str.append(quoteId.charAt(i));
            return str;
        }
        return quoteId;
    }

    // returns corresponding OB quote id of the quote that child order was targeting in its request
    CharSequence getTargetQuoteId(TORChildOrder childOrder) {
        if (childOrder.getExchangeId() != XBTO_EXCHANGE_ID)
            return childOrder.getQuoteId();

        StringBuilder sb = new StringBuilder(childOrder.getQuoteId());
        sb.append(":");
        sb.append((childOrder.getSide() == Side.BUY ? QuoteSide.ASK : QuoteSide.BID).name());
        return sb;
    }
}
