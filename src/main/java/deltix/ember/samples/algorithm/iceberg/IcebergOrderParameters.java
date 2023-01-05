package deltix.ember.samples.algorithm.iceberg;


import deltix.ember.message.trade.CustomAttribute;
import deltix.ember.message.trade.OrderEntryRequest;
import deltix.ember.service.algorithm.slicer.AlgoOrderParameters;

class IcebergOrderParameters extends AlgoOrderParameters {
    @Override
    protected void parseAttribute(CustomAttribute attribute, OrderEntryRequest request) {
        switch (attribute.getKey()) {
            default:
                super.parseAttribute(attribute, request);
        }
    }

    @Override
    public void copyFrom(AlgoOrderParameters other) {
        super.copyFrom(other);
    }

    @Override
    public void reset() {
        super.reset();
    }
}
