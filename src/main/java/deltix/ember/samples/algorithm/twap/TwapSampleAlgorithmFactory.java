package deltix.ember.samples.algorithm.twap;

import deltix.anvil.util.annotation.Optional;
import deltix.anvil.util.codec.AlphanumericCodec;
import deltix.ember.service.algorithm.AbstractAlgorithmFactory;
import deltix.ember.service.algorithm.Algorithm;
import deltix.ember.service.algorithm.AlgorithmContext;


public class TwapSampleAlgorithmFactory extends AbstractAlgorithmFactory {

    @Optional
    private String defaultOrderDestination;

    public String getDefaultOrderDestination() {
        return defaultOrderDestination;
    }

    public void setDefaultOrderDestination(final String defaultOrderDestination) {
        this.defaultOrderDestination = defaultOrderDestination;
    }


    @Override
    public TwapSampleAlgorithm create(AlgorithmContext context) {
        return new TwapSampleAlgorithm(context, getCacheSettings(), defaultOrderDestination);
    }

}
