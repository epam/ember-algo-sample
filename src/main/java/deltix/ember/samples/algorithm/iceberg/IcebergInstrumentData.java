package deltix.ember.samples.algorithm.iceberg;

import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.anvil.util.CharSequenceUtil;
import deltix.ember.message.smd.InstrumentAttribute;
import deltix.ember.message.smd.InstrumentType;
import deltix.ember.message.smd.InstrumentUpdate;
import deltix.ember.service.algorithm.md.SimpleInstrumentPrices;
import deltix.util.collections.generated.ObjectList;

class IcebergInstrumentData extends SimpleInstrumentPrices {

    /** Number of digits after decimal point */
    private int orderSizePrecision = 0;

    @Decimal
    private long minOrderSize = Decimal64Utils.ZERO;

    public IcebergInstrumentData(CharSequence symbol, InstrumentType instrumentType) {
        super(symbol, instrumentType);
    }


    @Decimal
    public long getMinOrderSize() {
        return minOrderSize;
    }

    public void setMinOrderSize(@Decimal long minOrderSize) {
        this.minOrderSize = minOrderSize;
    }

    public int getOrderSizePrecision() {
        return orderSizePrecision;
    }

    public void setOrderSizePrecision(int orderSizePrecision) {
        this.orderSizePrecision = orderSizePrecision;
    }


    @Override
    protected void update(InstrumentUpdate instrumentUpdate) {
        final ObjectList<InstrumentAttribute> attributes = instrumentUpdate.getAttributes();
        if (attributes != null) {
            for (int i = 0; i < attributes.size(); i += 1) {
                final InstrumentAttribute attribute = attributes.get(i);
                if (!attribute.hasKey() || !attribute.hasValue())
                    continue;

                if (CharSequenceUtil.equals("minOrderSize", attribute.getKey())) {
                    setMinOrderSize(Decimal64Utils.tryParse(attribute.getValue(), Decimal64Utils.ZERO));
                } else if (CharSequenceUtil.equals("orderSizePrecision", attribute.getKey()) && attribute.getValue() != null) {
                    setOrderSizePrecision(Integer.parseInt(attribute.getValue().toString()));
                }
            }
        }
    }

    /**
     * Round quantity using min order size and order size precision
     */
    @Decimal
    public long roundOrderQuantity(@Decimal long quantity) {
        // round active quantity using order size precision
        // round active quantity to the nearest value multiple of minimum order quantity
        if (Decimal64Utils.isZero(getMinOrderSize()))
            return Decimal64Utils.roundTowardsNegativeInfinity(quantity, Decimal64Utils.fromFixedPoint(1, getOrderSizePrecision()));

        return Decimal64Utils.roundToNearestTiesAwayFromZero(Decimal64Utils.roundTowardsNegativeInfinity(quantity, Decimal64Utils.fromFixedPoint(1, getOrderSizePrecision())), getMinOrderSize());
    }


}
