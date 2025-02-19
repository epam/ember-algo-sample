package deltix.ember.samples.algorithm.fakefeed;

import deltix.anvil.util.annotation.Optional;
import deltix.ember.service.algorithm.AbstractAlgorithmFactory;
import deltix.ember.service.algorithm.AlgorithmContext;

import java.util.List;

public class FakeFeedAlgorithmFactory extends AbstractAlgorithmFactory {

    private List<String> symbols;

    @Optional
    private String outputStream;

    @Optional
    private String outputTopic;

    @Optional
    private int messagesPerSecond = 100;

    @Optional
    private int orderBookDepth = 10;

    @Optional
    private boolean validateOutput;

    public void setSymbols(List<String> symbols) {
        this.symbols = symbols;
    }

    public void setOutputStream(String outputStream) {
        this.outputStream = outputStream;
    }

    public void setOutputTopic(String outputTopic) {
        this.outputTopic = outputTopic;
    }

    public void setMessagesPerSecond(int messagesPerSecond) {
        this.messagesPerSecond = messagesPerSecond;
    }

    public void setOrderBookDepth(int orderBookDepth) {
        this.orderBookDepth = orderBookDepth;
    }

    public void setValidateOutput(boolean validateOutput) {
        this.validateOutput = validateOutput;
    }

    @Override
    public FakeFeedAlgorithm create(AlgorithmContext context) {
        return new FakeFeedAlgorithm(context, symbols, outputTopic, outputStream, messagesPerSecond, orderBookDepth, validateOutput);
    }
}
