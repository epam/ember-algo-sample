package deltix.ember.samples.algorithm.positions;

import deltix.ember.service.algorithm.AbstractAlgorithmFactory;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.PositionTracker;

@PositionTracker
public class PositionsTrackingAlgorithmFactory extends AbstractAlgorithmFactory {

    @Override
    public PositionsTrackingAlgorithm create(AlgorithmContext context) {
        return new PositionsTrackingAlgorithm(context);
    }

}
