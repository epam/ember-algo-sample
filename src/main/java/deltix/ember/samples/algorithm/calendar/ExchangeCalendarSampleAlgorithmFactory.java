package deltix.ember.samples.algorithm.calendar;

import deltix.ember.service.algorithm.AbstractAlgorithmFactory;
import deltix.ember.service.algorithm.AlgorithmContext;

public class ExchangeCalendarSampleAlgorithmFactory extends AbstractAlgorithmFactory {

    @Override
    public ExchangeCalendarSampleAlgorithm create(AlgorithmContext context) {
        return new ExchangeCalendarSampleAlgorithm(context, getCacheSettings());
    }

}
