package deltix.ember.samples.algorithm.custominput;

import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.SimpleAlgorithm;
import deltix.ember.service.algorithm.SourcePoller;
import deltix.qsrv.hf.pub.InstrumentMessage;
import deltix.qsrv.hf.pub.MappingTypeLoader;

import java.util.function.Consumer;

/** Code snippet that illustrates use of SourcePollerBuilder */
public class CustomInputChannelAlgorithm2 extends SimpleAlgorithm {

    private final SourcePoller customSourcePoller;

    CustomInputChannelAlgorithm2(AlgorithmContext context, String customStreamKey) {
        super(context);

        // Step 1: create stream (if it doesn't exist) with CustomInputMessage format
        TimebaseUtils.getOrCreateStream(context._getTimeBase(), customStreamKey, CustomInputMessage.class);

        // Step 2: create a poller for TimeBase stream identified by given stream key
        MappingTypeLoader typeLoader = new MappingTypeLoader();
        typeLoader.bind("CustomInput", CustomInputMessage.class);

        Consumer<InstrumentMessage> messageConsumer = this::onCustomInputMessage; // callback that will process custom messages
        customSourcePoller = context
                .createInputPollerBuilder(customStreamKey)
                .with(typeLoader)
                .build(messageConsumer);
    }

    /** Callback that will process custom messages */
    private void onCustomInputMessage (InstrumentMessage message) {
        if (message instanceof CustomInputMessage) {
            CustomInputMessage customMessage = (CustomInputMessage) message;
            LOGGER.info("Custom message received %s %s").withTimestamp(message.getTimeStampMs()).with(customMessage.data);
        }
    }


}
