package deltix.ember.samples.algorithm.tor;

import deltix.ember.service.algorithm.AbstractAlgorithmFactory;
import deltix.ember.service.algorithm.Algorithm;
import deltix.ember.service.algorithm.AlgorithmContext;

/**
 * Targeted Order Routing Algorithm Factory - creates a sample algorithm that will target specific
 * order book quotes when issuing child orders
 */
public class TORAlgorithmFactory extends AbstractAlgorithmFactory {
    @Override
    public Algorithm create(AlgorithmContext context) {
        return new TORAlgorithm(context, getCacheSettings());
    }
}
