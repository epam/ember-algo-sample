package deltix.ember.service.algorithm.samples.t2o;

import deltix.ember.service.algorithm.AbstractAlgorithmFactory;
import deltix.ember.service.algorithm.AlgorithmContext;

import java.time.Duration;

@SuppressWarnings("unused")
public class TickToOrderAlgorithmFactory extends AbstractAlgorithmFactory {

    private int inOutRatio = 1000;
    private Duration submissionDelay = Duration.ofSeconds(15);
    private String destinationConnector = "cfh";
    private String destinationExchange = "CANCEL";

    public void setInOutRatio(int inOutRatio) {
        this.inOutRatio = inOutRatio;
    }

    public void setSubmissionDelay(Duration duration) {
        this.submissionDelay = duration;
    }

    public void setDestinationConnector(String destinationConnector) {
        this.destinationConnector = destinationConnector;
    }

    public void setDestinationExchange(String destinationExchange) {
        this.destinationExchange = destinationExchange;
    }

    @Override
    public TickToOrderAlgorithm create(AlgorithmContext context) {
        return new TickToOrderAlgorithm(context, getCacheSettings(), inOutRatio, submissionDelay, destinationConnector, destinationExchange);
    }
}
