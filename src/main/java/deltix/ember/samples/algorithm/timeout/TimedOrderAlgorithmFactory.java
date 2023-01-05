package deltix.ember.samples.algorithm.timeout;

import deltix.ember.service.algorithm.AbstractAlgorithmFactory;
import deltix.ember.service.algorithm.AlgorithmContext;

public class TimedOrderAlgorithmFactory extends AbstractAlgorithmFactory {

    @Override
    public TimedOrderAlgorithm create(AlgorithmContext context) {
        return new TimedOrderAlgorithm(context, getCacheSettings());
    }

}
