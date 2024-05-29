package deltix.ember.samples.algorithm.customoutput;

import deltix.anvil.util.CloseHelper;
import deltix.anvil.util.ReconnectDelay;
import deltix.anvil.util.annotation.Timestamp;
import deltix.anvil.util.delay.LinearDelay;
import deltix.anvil.util.timer.TimerCallback;
import deltix.data.stream.MessageChannel;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.SimpleAlgorithm;
import deltix.ember.timebase.util.TimebaseUtil;
import deltix.qsrv.hf.pub.md.RecordClassDescriptor;
import deltix.util.io.aeron.PublicationClosedException;
import deltix.util.lang.Util;

import java.util.concurrent.TimeUnit;

/**
 * This sample illustrates algorithm to algorithm communication via TimeBase Topics.
 * NOTE: Before using *topics* as means to communication consider using streams as simpler and less expensive
 * (in terms of CPU and operational overhead).
 *
 * This bit illustrates message producer side - algorithms that writes to special TimeBase topic.
 * For simplicity this sample publishes dummy messages into designated topic every 5 second.
 *
 */
@SuppressWarnings("rawtypes")
public class CustomOutputTopicAlgorithm extends SimpleAlgorithm  {
    private static final RecordClassDescriptor MESSAGE_FORMAT = TimebaseUtil.introspect(SampleMessage.class);
    private static final long FIVE_SECONDS_IN_MILLIS = TimeUnit.SECONDS.toMillis(5);

    private MessageChannel outputChannel;
    private final String topicKey;
    private final String carbonCopyStreamKey = null;
    private final ReconnectDelay reconnectDelay;
    private final SampleMessage message = new SampleMessage();
    private final TimerCallback<Void> periodicMessageSend = this::publishDummyMessage;

    public CustomOutputTopicAlgorithm(AlgorithmContext context, String topicKey) {
        super(context);
        this.topicKey = topicKey;
        this.reconnectDelay = new ReconnectDelay(context.getClock(), new LinearDelay(1000, 100000, 1000)); // how often to retry reconnect

        recreateOutputChannelIfNecessary();

        getTimer().schedule(currentTime() + FIVE_SECONDS_IN_MILLIS, periodicMessageSend, null);
    }

    public void close() {
        CloseHelper.close(outputChannel);
    }

    private @Timestamp long publishDummyMessage (@Timestamp long now, Void cookie) {
        assert isLeader() : "leader";

        // prepare payload
        message.setSequence(message.getSequence() + 1);

        // send
        if (outputChannel != null) {
            try {
                outputChannel.send (message);
                return FIVE_SECONDS_IN_MILLIS; // next message send
            } catch (PublicationClosedException e) {
                LOGGER.warn("Error writing output topic (will try to re-connect): %s").with(e);
                outputChannel = null;
            }
        }
        return now + 1; // retry soon
    }

    @Override
    public int doLast(int workDone) {
        int result = super.doLast(workDone);
        if (result == 0) {
            recreateOutputChannelIfNecessary();
        }
        return result;
    }

    private void recreateOutputChannelIfNecessary() {
        if (outputChannel == null) {
            if (reconnectDelay.expired()) {
                try {
                    reconnectDelay.onTry();
                    outputChannel = recreateOutputChannel();
                    reconnectDelay.onSuccess();
                } catch (Exception e) {
                    Util.close(outputChannel);
                    outputChannel = null;
                    reconnectDelay.onFail();
                    throw e;
                }
            }
        }
    }

    private MessageChannel recreateOutputChannel() {
        assert outputChannel == null;

        return context.createOutputTopicEx(
                topicKey,
                (topicSettings, loadingOptions) -> {
                    if (carbonCopyStreamKey != null)
                        topicSettings.setCopyToStream(carbonCopyStreamKey);
                },
                MESSAGE_FORMAT); // here we use single message, but polymorphic topics are supported
    }

}
