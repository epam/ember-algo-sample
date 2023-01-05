package deltix.ember.samples.algorithm.tor;

import deltix.ember.message.trade.OrderNewRequest;
import deltix.ember.service.algorithm.ChildOrder;

public class TORChildOrder extends ChildOrder<TOROrder> {

    private String quoteId;

    public TORChildOrder() {
        super();
    }

    public CharSequence getQuoteId() {
        return quoteId;
    }

    public void setQuoteId(CharSequence quoteId) {
        this.quoteId = (quoteId == null ? null : quoteId.toString());
    }

    @Override
    public void clear() {
        super.clear();
        quoteId = null;
    }

    @Override
    public void copyFrom(OrderNewRequest request) {
        super.copyFrom(request);
        setQuoteId(request.getQuoteId());
    }
}