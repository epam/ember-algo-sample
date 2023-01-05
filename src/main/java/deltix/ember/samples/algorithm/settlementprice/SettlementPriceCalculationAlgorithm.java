package deltix.ember.samples.algorithm.settlementprice;

import deltix.anvil.message.NodeStatusEvent;
import deltix.anvil.util.ReconnectDelay;
import deltix.anvil.util.annotation.Timestamp;
import deltix.anvil.util.clock.SystemEpochClock;
import deltix.anvil.util.delay.LinearDelay;
import deltix.anvil.util.timer.TimerCallback;
import deltix.data.stream.MessageChannel;
import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.ember.message.smd.InstrumentType;
import deltix.ember.message.smd.InstrumentTypeConverter;
import deltix.ember.service.algorithm.AbstractNonTradingAlgorithm;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.md.AbstractInstrumentData;
import deltix.ember.service.algorithm.md.InstrumentDataFactory;
import deltix.qsrv.hf.pub.InstrumentMessage;
import deltix.qsrv.hf.pub.md.Introspector;
import deltix.qsrv.hf.pub.md.RecordClassDescriptor;
import deltix.qsrv.hf.tickdb.pub.DXTickDB;
import deltix.qsrv.hf.tickdb.pub.StreamOptions;
import deltix.qsrv.hf.tickdb.pub.StreamScope;
import deltix.timebase.api.messages.TradeMessage;
import deltix.timebase.api.messages.universal.*;
import deltix.util.collections.generated.ObjectArrayList;
import deltix.util.io.aeron.PublicationClosedException;
import deltix.util.lang.Util;

import javax.annotation.Nonnull;
import java.time.LocalTime;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.function.BiPredicate;

class InstrumentPriceData extends AbstractInstrumentData {

    private @Decimal long lastTradePrice = Decimal64Utils.NULL;


    InstrumentPriceData(@Nonnull CharSequence symbol, InstrumentType instrumentType) {
        super(symbol, instrumentType);
    }

    @Override
    public void onMarketMessage(InstrumentMessage message) {
        if (message instanceof PackageHeader)
            processMessage((PackageHeader) message);
    }

    @SuppressWarnings("ForLoopReplaceableByForEach")
    private void processMessage(PackageHeader dataPackage) {
        List<BaseEntryInfo> packageEntries = dataPackage.getEntries();
        for (int i=0; i < packageEntries.size(); i++) {
            BaseEntryInfo packageEntry = packageEntries.get(i);
            if (packageEntry instanceof TradeEntry) {
                TradeEntry trade = (TradeEntry) packageEntry;
                lastTradePrice = trade.getPrice();
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    void publishAsTradeMessage (TradeMessage message, MessageChannel output) {
        if ( ! Decimal64Utils.isNaN(lastTradePrice)) {
            message.setSymbol(getSymbol());
            message.setInstrumentType(InstrumentTypeConverter.convert(getInstrumentType()));
            message.setPrice(Decimal64Utils.toDouble(lastTradePrice));
            message.setSize(1); //fake it
            output.send(message);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    void publishAsStatisticsEntry (PackageHeader message, StatisticsEntry entry, MessageChannel output) {
        if ( ! Decimal64Utils.isNaN(lastTradePrice)) {
            message.setSymbol(getSymbol());
            message.setInstrumentType(InstrumentTypeConverter.convert(getInstrumentType()));
            entry.setValue(lastTradePrice);
            output.send(message);
        }
    }
}

public class SettlementPriceCalculationAlgorithm extends AbstractNonTradingAlgorithm<InstrumentPriceData> {

    private final LocalTime publishTimeOfDay;
    private final String outputStreamKey;
    private final ReconnectDelay reconnectDelay;

    @SuppressWarnings({"rawtypes"})
    private MessageChannel outputChannel;

    private final TimerCallback<Void> publishTimer = this::publishPrices;
    private final BiPredicate<InstrumentPriceData,Void> snapshotPublisher;
    private final Calendar calendar;

    private final TradeMessage tradeMessage = new TradeMessage();

    private final StatisticsEntry statisticsEntry = new StatisticsEntry();
    private final PackageHeader statisticsEntryMessage = new PackageHeader(); {
        statisticsEntryMessage.setEntries(new ObjectArrayList<>(1));
        statisticsEntryMessage.getEntries().add(statisticsEntry);
        statisticsEntryMessage.setPackageType(PackageType.PERIODICAL_SNAPSHOT);
        statisticsEntry.setType(StatisticsType.SETTLEMENT_PRICE);
    }

    public SettlementPriceCalculationAlgorithm(AlgorithmContext context, String outputStreamKey, String publishTimeOfDay, String timezone, boolean publishSettlementAsStatisticsEntry) {
        super(context);
        this.publishTimeOfDay = LocalTime.parse(publishTimeOfDay);
        this.calendar = Calendar.getInstance(TimeZone.getTimeZone(timezone));
        this.outputStreamKey = outputStreamKey;
        this.reconnectDelay = new ReconnectDelay(context.getClock(), new LinearDelay(1000, 100000, 1000));
        this.snapshotPublisher = (publishSettlementAsStatisticsEntry) ? this::publishAsStatisticsEntry : this::publishAsTradeMessage;

        createOutputStreamIfNecessary((DXTickDB)context._getTimeBase(), outputStreamKey, publishSettlementAsStatisticsEntry);

        recreateOutputChannelIfNecessary();
    }

    @Override
    protected InstrumentDataFactory<InstrumentPriceData> createInstrumentDataFactory() {
        return InstrumentPriceData::new;
    }

    @Override
    public void onNodeStatusEvent(NodeStatusEvent event) {
        super.onNodeStatusEvent(event);
        if (isLeader()) {
            LOGGER.trace("Setting up publishing timer");

            getTimer().schedule(getFirstSettlementPublishTime(), publishTimer, null);
        }
    }

    @Override
    public int doLast(int workDone) {
        int result = super.doLast(workDone);
        if (result == 0) {
            recreateOutputChannelIfNecessary();
        }
        return result;
    }

    private @Timestamp long publishPrices (@Timestamp long now, Void cookie) {
        assert isLeader() : "leader";

        if (outputChannel != null) {
            LOGGER.info("Storing settlement prices into \"%s\" stream").with(outputStreamKey);

            this.tradeMessage.setTimeStampMs(now);
            this.statisticsEntryMessage.setTimeStampMs(now);

            try {
                assert marketDataProcessor != null;
                this.marketDataProcessor.iterate(snapshotPublisher, null);
                return getNextSettlementPublishTime();
            } catch (PublicationClosedException e) {
                LOGGER.warn("Error writing output stream (will try to re-connect): %s").with(e);
                outputChannel = null;
            }
        }
        return now + 1; // retry soon
    }

    private void recreateOutputChannelIfNecessary() {
        if (outputChannel == null) {
            if (reconnectDelay.expired()) {
                try {
                    reconnectDelay.onTry();
                    outputChannel = context.createOutputChannel(outputStreamKey);
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



    // Called by instrument iterator timer
    private boolean publishAsTradeMessage (InstrumentPriceData instrument, Void cookie) {
        instrument.publishAsTradeMessage(tradeMessage, outputChannel);
        return true;
    }

    private boolean publishAsStatisticsEntry (InstrumentPriceData instrument, Void cookie) {
        instrument.publishAsStatisticsEntry(statisticsEntryMessage, statisticsEntry, outputChannel);
        return true;
    }

    private static void createOutputStreamIfNecessary(DXTickDB timebase, String outputStreamKey, boolean publishSettlementAsStatisticsEntry) {
        if (timebase.getStream(outputStreamKey) == null) {
            try {
                StreamOptions options = new StreamOptions(StreamScope.DURABLE, outputStreamKey, "Auto-created by Ember", 1);
                options.unique = true;

                Introspector introspector = Introspector.createEmptyMessageIntrospector();
                RecordClassDescriptor rcd = introspector.introspectRecordClass("<Ember introspector>",
                        publishSettlementAsStatisticsEntry ? PackageHeader.class : TradeMessage.class);

//                if (publishSettlementAsStatisticsEntry) {
//                    RecordClassDescriptor statisticsRCD = introspector.introspectRecordClass(StatisticsEntry.class);
//                    ArrayDataType enriesFieldType = (ArrayDataType) rcd.getField("entries").getType();
//                    ClassDataType cdt = (ClassDataType) enriesFieldType.getElementDataType();
//                    RecordClassDescriptor [] descriptors = cdt.getDescriptors();
//                    boolean found = false;
//                    for (RecordClassDescriptor descriptor : descriptors) {
//                        if (descriptor.getName().equals(statisticsRCD.getName())) {
//                            found = true;
//                            break;
//                        }
//                    }
//                    if ( ! found) {
//                        assert descriptors.length > 0;
//                        descriptors[0] = statisticsRCD; // we are only going to use this entry type in this stream
//                    }
//                }
                options.setFixedType(rcd);
                timebase.createStream(outputStreamKey, options);
            } catch (Introspector.IntrospectionException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private @Timestamp long getFirstSettlementPublishTime() {
        final @Timestamp long now = SystemEpochClock.INSTANCE.time();
        calendar.setTimeInMillis(now);
        calendar.set(Calendar.HOUR_OF_DAY, publishTimeOfDay.getHour());
        calendar.set(Calendar.MINUTE, publishTimeOfDay.getMinute());
        calendar.set(Calendar.SECOND, publishTimeOfDay.getSecond());
        calendar.set(Calendar.MILLISECOND, 0);
        if (calendar.getTimeInMillis() <= now)
            calendar.add(Calendar.DAY_OF_MONTH, 1);

        return calendar.getTimeInMillis();
    }

    private @Timestamp long getNextSettlementPublishTime() {
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        return calendar.getTimeInMillis();
    }

}
