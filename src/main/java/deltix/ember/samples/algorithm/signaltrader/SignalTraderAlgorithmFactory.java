package deltix.ember.samples.algorithm.signaltrader;

import deltix.anvil.util.annotation.Optional;
import deltix.ember.service.algorithm.AbstractAlgorithmFactory;
import deltix.ember.service.algorithm.Algorithm;
import deltix.ember.service.algorithm.AlgorithmContext;

public class SignalTraderAlgorithmFactory extends AbstractAlgorithmFactory {


    @Optional
    private SignalTraderSettings signalSettings = new SignalTraderSettings();

    public void setSignalSettings(SignalTraderSettings signalSettings) {
        this.signalSettings = signalSettings;
    }

    @Override
    public Algorithm create(AlgorithmContext context) {
        return new SignalTraderAlgorithm(context, getCacheSettings(), signalSettings);
    }
}
