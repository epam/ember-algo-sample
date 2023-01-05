package deltix.ember.samples.algorithm.tor;

import deltix.anvil.util.Factory;
import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.ember.message.trade.*;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.ChildOrder;
import deltix.ember.service.algorithm.md.InstrumentDataFactory;
import deltix.ember.service.algorithm.slicer.SlicingAlgorithm;
import deltix.ember.service.data.OrderState;
import deltix.ember.service.oms.cache.OrdersCacheSettings;
import deltix.gflog.LogLevel;

import java.util.function.Function;

/**
 * Targeted Order Routing Algorithm - a sample algorithm that can target specific
 * order book quotes when issuing child orders
 */
public class TORAlgorithm extends SlicingAlgorithm<TOROrder, TORInstrumentData> {

    public TORAlgorithm(AlgorithmContext context, OrdersCacheSettings cacheSettings) {
        super(context, cacheSettings);
    }

    @Override
    protected CharSequence processNewOrder(TOROrder parent, OrderNewRequest request) {
        if (request.getTimeInForce() != TimeInForce.IMMEDIATE_OR_CANCEL)
            return "Only IOC orders are accepted";

        return super.processNewOrder(parent, request);
    }

    @Override
    protected CharSequence processReplace(TOROrder parent, OrderReplaceRequest request) {
        return "Not supported";
    }


    @Override
    protected void submitMoreChildrenIfNecessary(TOROrder parent) {
        // match only once for IOC orders
        if (parent.getInstrumentData() == null) {
            getOrCreate(parent.getSymbol(), parent.getInstrumentType()).match(parent);
        }
        checkParentOrder(parent);
    }

    private void checkParentOrder(final TOROrder parent) {
        if (parent.isFinal()) {
            if (LOGGER.isEnabled(LogLevel.INFO)) {
                if (parent.getState() == OrderState.COMPLETELY_FILLED)
                    LOGGER.info().append('[').append(parent.getOrderId()).append("] Order is fully filled.").commit();
                else
                    LOGGER.info().append('[').append(parent.getOrderId()).append("] Order is ")
                            .append(parent.getState()).append(". Remaining Quantity: ")
                            .appendDecimal64(parent.getRemainingQuantity()).commit();
            }
        }
        // if non-final IOC order has no children the remainder can be cancelled
        else if (!parent.hasActiveChildren()) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info().append('[').append(parent.getOrderId())
                        .append("] Order remainder is canceled. Remaining Quantity: ")
                        .appendDecimal64(parent.getRemainingQuantity()).commit();
            }
            cancelAlgoOrder(parent, "No more market");
        }
    }

    @Override
    protected void commitReplace(TOROrder parent) {
        // only IOC orders are supported
    }

    void submitChildOrder(TOROrder parent, long exchangeId, CharSequence quoteId, @Decimal long quotedPrice, @Decimal long quantity) {
        assert Decimal64Utils.isPositive(quantity) : "Zero trade quantity";

        MutableOrderNewRequest childRequest = makeChildOrderRequest(parent);
        childRequest.setTimeInForce(TimeInForce.IMMEDIATE_OR_CANCEL);
        childRequest.setOrderType(OrderType.LIMIT);
        childRequest.setLimitPrice(quotedPrice);
        childRequest.setExchangeId(exchangeId);
        childRequest.setDestinationId(exchangeId);
        childRequest.setQuantity(quantity);
        childRequest.setQuoteId(quoteId);
        submit(parent, childRequest);
        // childOrder.setQuoteId(quoteId);

        if (LOGGER.isEnabled(LogLevel.INFO)) {
            LOGGER.info().append("[").append(parent.getOrderId()).append("] Sending order request (")
                    .append("#").append(childRequest.getOrderId())
                    .append(") to ").append(childRequest.getSide())
                    .append(" '").append(childRequest.getSymbol()).append("' ")
                    .appendDecimal64(childRequest.getQuantity()).append(" @ ")
                    .appendDecimal64(childRequest.getLimitPrice())
                    .append(" (Exchange: ").appendAlphanumeric(childRequest.getExchangeId())
                    .append(", Quote ID: ").append(childRequest.getQuoteId())
                    .append(", TimeInForce: ").append(childRequest.getTimeInForce()).append(")").commit();
        }
    }


    @Override
    protected Factory<TOROrder> createParentOrderFactory() {
        return TOROrder::new;
    }

    @Override
    protected Factory<ChildOrder<TOROrder>> createChildOrderFactory() {
        return TORChildOrder::new;
    }

    @Override
    protected InstrumentDataFactory<TORInstrumentData> createInstrumentDataFactory() {
        return (symbol, instrumentType) -> new TORInstrumentData(symbol, instrumentType,this);
    }

    @Override
    protected void onLeaderState(TOROrder parent) {
        parent.setInstrumentData(getOrCreate(parent.getSymbol(), parent.getInstrumentType()));
        super.onLeaderState(parent);
    }
}
