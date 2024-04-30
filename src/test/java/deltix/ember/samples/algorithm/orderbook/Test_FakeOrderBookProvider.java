package deltix.ember.samples.algorithm.orderbook;

import com.epam.deltix.dfp.Decimal64Utils;
import deltix.anvil.util.annotation.Alphanumeric;
import deltix.anvil.util.codec.AlphanumericCodec;
import deltix.quoteflow.orderbook.ExchangeOrderBook;
import deltix.quoteflow.orderbook.FullOrderBook;
import deltix.quoteflow.orderbook.interfaces.OrderBookList;
import deltix.quoteflow.orderbook.interfaces.OrderBookQuote;
import deltix.timebase.api.messages.QuoteSide;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class Test_FakeOrderBookProvider {



    @Test
    public void basicTest () {
        @Alphanumeric
        final long exchangeId = AlphanumericCodec.encode("COINBASE");
        FullOrderBook book = FakeOrderBookProvider.createFakeBook("BTCUSD", 10.0, 1,100.0, 1, exchangeId);

        OrderBookList<OrderBookQuote> bids = book.getAllQuotes(QuoteSide.BID);
        assertNotNull(bids);
        assertEquals (1, bids.size());
        OrderBookQuote bb = bids.getObjectAt(0);
        assertEquals (exchangeId, bb.getExchangeId());
        assertEquals ("10", Decimal64Utils.toString(bb.getPrice()));
        assertEquals ("1", Decimal64Utils.toString(bb.getSize()));

        OrderBookList<OrderBookQuote> offers = book.getAllQuotes(QuoteSide.ASK);
        assertNotNull(offers);
        assertEquals (1, offers.size());
        OrderBookQuote bo = offers.getObjectAt(0);
        assertEquals (exchangeId, bo.getExchangeId());
        assertEquals ("100", Decimal64Utils.toString(bo.getPrice()));
        assertEquals ("1", Decimal64Utils.toString(bo.getSize()));

        ExchangeOrderBook exchangeBook = book.getExchange(exchangeId);

        bids = exchangeBook.getAllQuotes(QuoteSide.BID);
        assertNotNull(bids);
        assertEquals (1, bids.size());
        bb = bids.getObjectAt(0);
        assertEquals (exchangeId, bb.getExchangeId());
        assertEquals ("10", Decimal64Utils.toString(bb.getPrice()));
        assertEquals ("1", Decimal64Utils.toString(bb.getSize()));

        offers = exchangeBook.getAllQuotes(QuoteSide.ASK);
        assertNotNull(offers);
        assertEquals (1, offers.size());
        bo = offers.getObjectAt(0);
        assertEquals (exchangeId, bo.getExchangeId());
        assertEquals ("100", Decimal64Utils.toString(bo.getPrice()));
        assertEquals ("1", Decimal64Utils.toString(bo.getSize()));


    }
}
