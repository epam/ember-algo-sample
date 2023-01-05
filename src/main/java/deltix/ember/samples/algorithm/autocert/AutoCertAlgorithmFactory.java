package deltix.ember.samples.algorithm.autocert;

import deltix.anvil.util.annotation.Optional;
import deltix.ember.service.algorithm.AbstractAlgorithmFactory;
import deltix.ember.service.algorithm.AlgorithmContext;


public class AutoCertAlgorithmFactory extends AbstractAlgorithmFactory {

    @Optional
    private int orderScenarioTag = 8076;

    public void setOrderScenarioTag(int orderScenarioTag) {
        this.orderScenarioTag = orderScenarioTag;
    }

    @Override
    public AutoCertAlgorithm create(AlgorithmContext context) {
        return new AutoCertAlgorithm(context, getCacheSettings(), orderScenarioTag);
    }
}
