package deltix.ember.samples.algorithm.custominput;

import deltix.anvil.util.CloseHelper;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.SimpleAlgorithm;
import deltix.ember.service.algorithm.SourcePoller;
import deltix.qsrv.hf.pub.InstrumentMessage;

import java.util.function.Consumer;

class CustomInputChannelAlgorithm extends SimpleAlgorithm {

    private final SourcePoller customSourcePoller;

    CustomInputChannelAlgorithm(AlgorithmContext context, String customStreamKey) {
        super(context);

        // callback that will process custom messages
        Consumer<InstrumentMessage> messageConsumer = this::onCustomInputMessage;

        // here we create a poller for TimeBase stream identified by given stream key
        customSourcePoller = context.createInputPoller(customStreamKey, messageConsumer);
    }

    /** Callback that will process custom messages */
    private void onCustomInputMessage (InstrumentMessage message) {
        // This is just a sample. In real life avoid logging high rate messages
        LOGGER.info("Custom message received %s %s").withTimestamp(message.getTimeStampMs()).with(message.getSymbol());
    }

    /** Callback of algorithm worker thread */
    @Override
    public int doLast(int workDone) {
        int newWorkDone = super.doLast(workDone); // don't forget to call parent method - otherwise things like timers won't work

        // check if input stream has any new messages
        newWorkDone += customSourcePoller.poll(32);

        return newWorkDone;
    }

    @Override
    public void close() {
        CloseHelper.close(customSourcePoller); // don't forget to close - to free up timebase resources!
    }


}
