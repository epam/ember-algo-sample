package deltix.ember.samples.algorithm.tor;

import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.ember.message.trade.OrderType;
import deltix.ember.message.trade.Side;
import deltix.ember.message.trade.TimeInForce;
import deltix.ember.service.algorithm.ChildOrder;
import deltix.ember.service.algorithm.slicer.AlgoOrderParameters;
import deltix.ember.service.algorithm.slicer.SlicingAlgoOrder;

public class TOROrder extends SlicingAlgoOrder<AlgoOrderParameters> {

    private TORInstrumentData instrumentData;

    public TOROrder() {
        super(AlgoOrderParameters::new);
    }

    TORInstrumentData getInstrumentData() {
        return instrumentData;
    }

    void setInstrumentData(TORInstrumentData instrumentData) {
        this.instrumentData = instrumentData;
    }

    @Override
    public void addActiveChild(ChildOrder child) {
        super.addActiveChild(child);
        if (instrumentData != null)
            instrumentData.onChildOrderAdded((TORChildOrder) child);
    }

    @Override
    public void removeActiveChild(ChildOrder child) {
        super.removeActiveChild(child);
        if (instrumentData != null)
            instrumentData.onChildOrderRemoved((TORChildOrder) child);
    }

    @Override
    public void clear() {
        super.clear();
        instrumentData = null;
    }

    @Decimal
    long getAvailableQuantity() {
        return Decimal64Utils.subtract(getRemainingQuantity(), getRemainingChildrenQuantity());
    }

    public boolean isMarketOrder() {
        return (getOrderType() == OrderType.MARKET) ;
    }

    public boolean isAcceptablePrice(@Decimal long price) {
        return isMarketOrder() || !isBetterPrice(getWorkingOrder().getLimitPrice(), price);
    }

    public boolean isBetterPrice(@Decimal long price1, @Decimal long price2) {
        return (getSide() == Side.BUY) ? Decimal64Utils.isLess(price1, price2) : Decimal64Utils.isGreater(price1, price2);
    }
}
