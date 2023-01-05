package deltix.ember.samples.algorithm.custominput;

import deltix.anvil.util.CloseHelper;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.SimpleAlgorithm;
import deltix.ember.service.algorithm.SourcePoller;
import deltix.qsrv.hf.pub.InstrumentMessage;
import deltix.qsrv.hf.pub.md.Introspector;
import deltix.qsrv.hf.pub.md.RecordClassDescriptor;
import deltix.qsrv.hf.tickdb.pub.*;

import java.util.function.Consumer;



/** Same as CustomInputChannelAlgorithm but we also show how to auto-create stream using Timebase API */
public class CustomInputChannelAlgorithmEx extends SimpleAlgorithm {

    private final SourcePoller customSourcePoller;

    CustomInputChannelAlgorithmEx(AlgorithmContext context, String customStreamKey) {
        super(context);

        // Step 1: create stream (if it doesn't exist) with CustomInputMessage format
        TimebaseUtils.getOrCreateStream(context._getTimeBase(), customStreamKey, CustomInputMessage.class);

        // Step 2: create a poller for TimeBase stream identified by given stream key
        Consumer<InstrumentMessage> messageConsumer = this::onCustomInputMessage; // callback that will process custom messages
        customSourcePoller = context.createInputPoller(customStreamKey, messageConsumer);
    }

    /** Callback that will process custom messages */
    private void onCustomInputMessage (InstrumentMessage message) {
        if (message instanceof CustomInputMessage) {
            CustomInputMessage customMessage = (CustomInputMessage) message;
            LOGGER.info("Custom message received %s %s").withTimestamp(message.getTimeStampMs()).with(customMessage.data);
        }
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

/** Just a few helper methods around Timebase Java API */
class TimebaseUtils {

    public static WritableTickStream getOrCreateStream(WritableTickDB timebase, String streamKey, Class<?>... messageTypes) {
        WritableTickStream result = timebase.getStream(streamKey);
        if (result == null) {
            StreamOptions options = new StreamOptions(StreamScope.DURABLE, streamKey, "Auto-created by Ember", 1);
            assert messageTypes.length > 0;
            if (messageTypes.length == 1)
                options.setFixedType(introspect(messageTypes[0]));
            else
                options.setPolymorphic(introspect(messageTypes));
            result = ((DXTickDB)timebase).createStream(streamKey, options);
        }
        return result;
    }


    public static RecordClassDescriptor[] introspect(Class ... messageClass) {
        try {
            Introspector introspector = Introspector.createEmptyMessageIntrospector();
            RecordClassDescriptor [] result = new RecordClassDescriptor [messageClass.length];
            for (int i=0; i < result.length; i++)
                result[i] = introspector.introspectRecordClass("Ember introspector", messageClass[i]);

            return result; // or introspector.getRecordClasses() ?
        } catch (Introspector.IntrospectionException e) {
            throw new RuntimeException(e);
        }
    }

    public static RecordClassDescriptor introspect(Class<?> messageClass) {
        try {
            return Introspector.createEmptyMessageIntrospector().introspectRecordClass("<Ember introspector>", messageClass);
        } catch (Introspector.IntrospectionException e) {
            throw new RuntimeException(e);
        }
    }
}
