package deltix.ember.samples.algorithm.orderbook;

import com.epam.deltix.dfp.Decimal64Utils;
import deltix.anvil.util.annotation.Alphanumeric;
import deltix.quoteflow.orderbook.FullOrderBook;
import deltix.timebase.api.messages.QuoteSide;
import deltix.timebase.api.messages.universal.L2EntryNew;
import deltix.timebase.api.messages.universal.PackageHeader;
import deltix.timebase.api.messages.universal.PackageType;
import deltix.util.collections.generated.ObjectArrayList;

public class FakeOrderBookProvider {
    public static FullOrderBook createFakeBook (String symbol, double bestBidPrice, double bestBidSize, double bestOfferPrice, double bestOfferSize, @Alphanumeric long exchangeId) {

        PackageHeader m = new PackageHeader();
        m.setSymbol(symbol);
        m.setPackageType(PackageType.VENDOR_SNAPSHOT);
        m.setInstrumentType(deltix.qsrv.hf.pub.InstrumentType.FX);
        m.setTimeStampMs(System.currentTimeMillis());

        m.setEntries(new ObjectArrayList<>());

        L2EntryNew bb = new L2EntryNew();
        bb.setSide(QuoteSide.BID);
        bb.setLevel((short)0);
        bb.setPrice(Decimal64Utils.fromDouble(bestBidPrice));
        bb.setSize(Decimal64Utils.fromDouble(bestBidSize));
        bb.setExchangeId(exchangeId);
        bb.setNumberOfOrders(1);
        m.getEntries().add(bb);

        L2EntryNew bo = new L2EntryNew();
        bo.setSide(QuoteSide.ASK);
        bo.setLevel((short)0);
        bo.setPrice(Decimal64Utils.fromDouble(bestOfferPrice));
        bo.setSize(Decimal64Utils.fromDouble(bestOfferSize));
        bo.setExchangeId(exchangeId);
        bo.setNumberOfOrders(1);
        m.getEntries().add(bo);

        FullOrderBook result = new FullOrderBook(symbol);
        result.update(m);
        return result;

    }
}
