package deltix.ember.service.algorithm.samples.lp;

import deltix.ember.service.algorithm.AbstractAlgorithmFactory;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.oms.cache.OrdersCacheSettings;

public class PerExchangeMetadataSampleAlgorithmFactory extends AbstractAlgorithmFactory {

    @Override
    public PerExchangeMetadataSampleAlgorithm create(AlgorithmContext context) {
        return new PerExchangeMetadataSampleAlgorithm(context, OrdersCacheSettings.makeDefault());
    }

}