package deltix.ember.samples.algorithm.bollinger.v1;

import deltix.ember.samples.algorithm.bollinger.BollingerBandSettings;
import deltix.ember.service.algorithm.AbstractAlgorithmFactory;
import deltix.ember.service.algorithm.Algorithm;
import deltix.ember.service.algorithm.AlgorithmContext;

@SuppressWarnings("unused")
public class BollingerBandAlgorithmFactory extends AbstractAlgorithmFactory {

    private BollingerBandSettings bandSettings = new BollingerBandSettings();

    public void setBandSettings(BollingerBandSettings bandSettings) {
        this.bandSettings = bandSettings;
    }

    @Override
    public Algorithm create(AlgorithmContext context) {
        return new BollingerBandAlgorithm(context, getCacheSettings(), bandSettings);
    }
}
