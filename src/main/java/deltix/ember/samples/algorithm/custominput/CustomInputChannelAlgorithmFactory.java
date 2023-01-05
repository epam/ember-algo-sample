package deltix.ember.samples.algorithm.custominput;

import deltix.anvil.util.annotation.Required;
import deltix.ember.service.algorithm.AbstractAlgorithmFactory;
import deltix.ember.service.algorithm.Algorithm;
import deltix.ember.service.algorithm.AlgorithmContext;

public class CustomInputChannelAlgorithmFactory extends AbstractAlgorithmFactory {

    @Required
    private String customStreamKey;

    // called by config loader
    public void setCustomStreamKey(String customStreamKey) {
        this.customStreamKey = customStreamKey;
    }

    @Override
    public Algorithm create(AlgorithmContext context) {
        return new CustomInputChannelAlgorithm (context, customStreamKey);
    }
}


