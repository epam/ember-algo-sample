package deltix.ember.samples.algorithm.customattr;

import deltix.anvil.util.Factory;
import deltix.anvil.util.codec.AlphanumericCodec;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.efix.message.field.ExecInst;
import deltix.efix.message.field.Tag;
import deltix.ember.message.trade.*;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.md.AbstractInstrumentData;
import deltix.ember.service.algorithm.md.InstrumentData;
import deltix.ember.service.algorithm.md.InstrumentDataFactory;
import deltix.ember.service.algorithm.util.OrderAttributesParser;
import deltix.ember.service.algorithm.v2.AbstractTradingAlgorithm;
import deltix.ember.service.algorithm.v2.order.Order;
import deltix.ember.service.algorithm.v2.order.OrderEntryReq;
import deltix.ember.service.algorithm.v2.order.OutboundOrder;
import deltix.ember.util.CustomAttributeListBuilder;
import deltix.ember.util.EnumCustomAttributeHelper;
import deltix.qsrv.hf.pub.InstrumentMessage;

import javax.annotation.Nonnull;
import java.util.function.Function;

/**
 * In this example we show how to keep custom attributes for outbound orders.
 * Note that if you just want to send an order with custom attributes to execution venue there is much easier way to do it.
 * Simply set them on order request and forget about them. This this is a more complicated example.
 * Here we assume that you need to reference order attributes during life of your order (e.g. they affect business logic).
 *
 * First of all we need a custom class that represent our order.
 */
class MyOrder extends OutboundOrder {

    enum Reason {
        OpenPosition,
        TakeProfit,
        StopLoss
    }

    /** This is custom attribute we want to use */
    Reason reason;
}


/** The only way to persist custom order information is storing it in custom attributes of corresponding OrderNewRequest */
class MyOrderEntryReq extends OrderEntryReq {

    private final EnumCustomAttributeHelper<MyOrder.Reason> reasonLookup = new EnumCustomAttributeHelper<>(MyOrder.Reason.class);

    /**
     * Deltix trading model borrows a lot of concept from FIX protocol. Custom attributes correspond to custom FIX tags.
     * For custom tags Deltix recommends using range from 7000-9999. Different order types may use overlapping tags.
     */
    static final int REASON_CUSTOM_ATTRIBUTE_TAG = 8001;

    @Override
    public OrderEntryReq copyFrom(OrderNewRequest request, Order order) {
        if (request.hasAttributes()) {
            CharSequence reasonAsText = OrderAttributesParser.getCustomAttribute(request.getAttributes(), REASON_CUSTOM_ATTRIBUTE_TAG);
            ((MyOrder)order).reason = reasonLookup.valueOf (reasonAsText);
        }

        return super.copyFrom(request, order);
    }
}


/**
 * Algorithm itself. Here we send orders on some random condition from market data.
 *
 */
public class AlgortithmWithCustomOrderAttributes extends AbstractTradingAlgorithm<InstrumentData, MyOrder> {

    private final CustomAttributeListBuilder customAttributesBuilder = new CustomAttributeListBuilder();

    public AlgortithmWithCustomOrderAttributes(AlgorithmContext context) {
        super(context);
    }

    /** You need to tell framework how to create your custom order */
    @Override
    protected Factory<MyOrder> createOrderFactory() {
        return MyOrder::new;
    }

    /** You need to tell framework how to create your custom order request entry */
    @Override
    protected Factory<OrderEntryReq> createOrderEntryReqFactory() {
        return MyOrderEntryReq::new;
    }

    @Override
    protected InstrumentDataFactory<InstrumentData> createInstrumentDataFactory() {
        return (symbol, instrumentType) -> new AbstractInstrumentData(symbol, instrumentType) {
            @Override
            public void onMarketMessage(InstrumentMessage message) {
                if (getClock().time() % 152 == 0) { // crazy sample, ignore
                    MyOrder order = submit(createNewOrderRequest(Side.BUY, 10, "BTCUSD", MyOrder.Reason.OpenPosition));
                    // We can also initialize order field here:
                    //
                    //    order.reason = OpenPosition
                    //
                    // but this will cover only live orders (and will not work for orders that remain active after restart).
                }
            }
        };
    }

    @Nonnull
    private MutableOrderNewRequest createNewOrderRequest(Side side, int size, String symbol, MyOrder.Reason reason) {
        MutableOrderNewRequest request = new MutableOrderNewRequest();
        request.setOrderId(context.getRequestSequence().next());
        request.setSide(side);
        request.setQuantity(Decimal64Utils.fromLong((long) size));
        request.setSymbol(symbol);
        request.setTimeInForce(TimeInForce.IMMEDIATE_OR_CANCEL);
        request.setOrderType(OrderType.MARKET);
        request.setDestinationId(AlphanumericCodec.encode("SIM"));
        request.setSourceId(getId()); // Identify order source
        request.setTimestamp(System.currentTimeMillis());

        // Here we show to to set custom attributes on order request
        request.setAttributes(customAttributesBuilder
                .clear()
                .addEnum(MyOrderEntryReq.REASON_CUSTOM_ATTRIBUTE_TAG, reason) // this will persist order's reason
                .addInteger(Tag.ExecInst, ExecInst.DO_NOT_REDUCE)
                .build());

        return request;
    }
}

