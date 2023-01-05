package deltix.ember.samples.algorithm.iceberg;

import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.ember.service.algorithm.slicer.SlicingAlgoOrder;

public final class IcebergOrder extends SlicingAlgoOrder<IcebergOrderParameters> {

    private IcebergInstrumentData instrument;

    public IcebergOrder() {
        super(IcebergOrderParameters::new);
    }

    public IcebergInstrumentData getInstrument() {
        return instrument;
    }

    public void setInstrument(IcebergInstrumentData instrument) {
        this.instrument = instrument;
    }

    @Decimal
    public long getDisplayQuantity() {
        @Decimal final long result = getWorkingOrder().getDisplayQuantity();
        return Decimal64Utils.isNaN(result) || Decimal64Utils.isZero(result) ? getWorkingQuantity() : result;
    }

    @Override
    public void clear() {
        super.clear();
        instrument = null;
    }
}

