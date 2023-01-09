package deltix.ember.service.algorithm.samples.sim;

import deltix.ember.service.algorithm.AbstractAlgorithmFactory;
import deltix.ember.service.algorithm.AlgorithmContext;

public class ExchangeSimulationAlgorithmFactory extends AbstractAlgorithmFactory {

    @Override
    public ExchangeSimulationAlgorithm create(AlgorithmContext context) {
        return new ExchangeSimulationAlgorithm(context, getCacheSettings());
    }

}
