package deltix.ember.samples.algorithm.fakefeed;

import deltix.anvil.message.MutableShutdownResponse;
import deltix.anvil.message.NodeStatus;
import deltix.anvil.message.NodeStatusEvent;
import deltix.anvil.message.ShutdownRequest;
import deltix.anvil.service.AbstractService;
import deltix.anvil.service.ServiceWorkerAware;
import deltix.anvil.util.CharSequenceUtil;
import deltix.anvil.util.annotation.Timestamp;
import deltix.anvil.util.timer.ExclusiveTimer;
import deltix.anvil.util.timer.TimerCallback;
import deltix.data.stream.MessageChannel;
import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.ember.message.risk.KillSwitchEvent;
import deltix.ember.message.smd.*;
import deltix.ember.message.trade.*;
import deltix.ember.message.trade.oms.BinaryMessage;
import deltix.ember.message.trade.oms.PositionReport;
import deltix.ember.service.EmberConstants;
import deltix.ember.service.algorithm.Algorithm;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.util.LogProcessorAdapter;
import deltix.ember.service.oms.TradingUtils;
import deltix.qsrv.hf.pub.InstrumentMessage;
import deltix.qsrv.hf.pub.InstrumentType;
import deltix.qsrv.hf.tickdb.pub.LoadingOptions;
import deltix.qsrv.hf.tickdb.pub.topic.settings.TopicSettings;
import deltix.quoteflow.utils.validators.L2DataValidator;
import deltix.timebase.api.messages.BookUpdateAction;
import deltix.timebase.api.messages.QuoteSide;
import deltix.timebase.api.messages.universal.*;
import deltix.util.collections.CharSequenceToObjectMapQuick;
import deltix.util.collections.generated.ObjectArrayList;

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** Algorithm that produces mockup data feed (Level2) for FIX Gateway Market Data demo */
class FakeFeedAlgorithm extends AbstractService implements Algorithm, ServiceWorkerAware {

    private static final int MAX_MESSAGES_AT_ONCE = 10;
    private static final int NUMBER_OF_ENTRIES_IN_UPDATE = 2;
    private static final int MAX_ENTRY_SIZE = 10;
    private static final long SNAPSHOT_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(15);
    private static final long TICK_PRICE = Decimal64Utils.parse("0.001");
    private static final long TICK_SIZE = Decimal64Utils.parse("0.1");

    private final ExclusiveTimer timer = new ExclusiveTimer(64);
    private final Random rnd = new Random(152);
    private final AlgorithmContext context;
    private final MessageChannel<InstrumentMessage> output;
    private final String[] symbols;
    private final PackageHeader snapshotMessage = new PackageHeader();
    private final PackageHeader updateMessage = new PackageHeader();
    private final ObjectArrayList<BaseEntryInfo> snapshotEntries;
    private final ObjectArrayList<BaseEntryInfo> updateEntries;
    private boolean isLeader;
    private final CharSequenceToObjectMapQuick<InstrumentType> instrumentTypes = new CharSequenceToObjectMapQuick<>();

    private final long sendIntervalNs;
    private long nextSendNs;

    @SuppressWarnings("FieldCanBeLocal")
    private final TimerCallback<Void> snapshotCallback = this::sendSnapshotOnTimer;

    private final L2DataValidator[] validators;

    @SuppressWarnings("unchecked")
    public FakeFeedAlgorithm(AlgorithmContext context, List<String> symbols, String outputTopic, String outputStream, int messagesPerSecond, int orderBookDepth, boolean validateOutput) {
        super(context.getId(), context.getName());

        this.context = context;
        this.sendIntervalNs = TimeUnit.SECONDS.toNanos(1) / messagesPerSecond;
        this.nextSendNs = System.nanoTime() + sendIntervalNs;

        if (symbols == null || symbols.isEmpty())
            throw new IllegalArgumentException("Algorithm subscription symbols must be defined");
        this.symbols = symbols.toArray(new String[0]);
        if (outputTopic != null) {
            final BiConsumer<TopicSettings, LoadingOptions> settings = (topicSettings, loadingOptions) -> {
                // do nothing
            };
            this.output = (MessageChannel<InstrumentMessage>) context.createOutputTopic(outputTopic, settings, PackageHeader.class);
        } else {
            assert outputStream != null;
            this.output = (MessageChannel<InstrumentMessage>) context.createOutputChannel(outputStream, PackageHeader.class);
        }

        this.validators = (validateOutput) ? makeValidators(symbols) : null;

        final @Decimal long midPointPrice = Decimal64Utils.fromLong(1000);
        {
            snapshotEntries = new ObjectArrayList<>(2 * orderBookDepth);
            snapshotMessage.setPackageType(PackageType.VENDOR_SNAPSHOT);
            snapshotMessage.setEntries(snapshotEntries);
            snapshotMessage.setSourceId(context.getId());

            for (int i=0; i < orderBookDepth; i++) {
                L2EntryNew entry = new L2EntryNew();
                entry.setPrice(Decimal64Utils.subtract(midPointPrice, Decimal64Utils.multiplyByInteger(TICK_PRICE, i+1)));
                entry.setSize(Decimal64Utils.ONE);
                entry.setSide(QuoteSide.BID);
                entry.setLevel((short)i);
                entry.setExchangeId(context.getId());
                snapshotEntries.add(entry);
            }
            for (int i=0; i < orderBookDepth; i++) {
                L2EntryNew entry = new L2EntryNew();
                entry.setPrice(Decimal64Utils.add(midPointPrice, Decimal64Utils.multiplyByInteger(TICK_PRICE, i+1)));
                entry.setSide(QuoteSide.ASK);
                entry.setSize(Decimal64Utils.ONE);
                entry.setLevel((short)i);
                entry.setExchangeId(context.getId());
                snapshotEntries.add(entry);
            }
        }

        {
            updateEntries = new ObjectArrayList<>(2);
            updateMessage.setPackageType(PackageType.INCREMENTAL_UPDATE);
            updateMessage.setEntries(updateEntries);
            updateMessage.setSourceId(context.getId());

            L2EntryUpdate updateBid = new L2EntryUpdate();
            updateBid.setSide(QuoteSide.BID);
            updateBid.setAction(BookUpdateAction.UPDATE);
            updateBid.setExchangeId(context.getId());
            updateEntries.add(updateBid);
            L2EntryUpdate updateAsk = new L2EntryUpdate();
            updateAsk.setSide(QuoteSide.ASK);
            updateAsk.setAction(BookUpdateAction.UPDATE);
            updateAsk.setExchangeId(context.getId());
            updateEntries.add(updateAsk);
        }

        timer.schedule(context.getClock().time(), snapshotCallback, null);
    }

    private L2DataValidator[] makeValidators(List<String> symbols) {
        L2DataValidator [] result = new L2DataValidator [symbols.size()];

        LogProcessorAdapter logger = new LogProcessorAdapter (context.getLogger());
        for (int i=0; i < symbols.size(); i++) {
            result[i] = new L2DataValidator(symbols.get(i), logger, TICK_PRICE, TICK_SIZE, true);
        }
        return result;
    }

    @Override
    public void open() {
        if (context.getInstrumentSnapshot() != null) {
            context.getInstrumentSnapshot().forEach(this);
        }

        // Strictly speaking this is not a correct place to start publishing feed (esp for Follower mode)
        // but this algorithm is mockup data feed used for FIX Gateway Market Data demo
        sendAllMarketDataSnapshots();
    }

    @Override
    public void onNodeStatusEvent(NodeStatusEvent event) {
        isLeader = event.getNodeStatus() == NodeStatus.LEADER;
    }

    @Override
    public void onShutdownRequest(ShutdownRequest request) {
        final MutableShutdownResponse response = new MutableShutdownResponse();
        response.setServiceId(id);

        context.getShutdownResponseHandler().onShutdownResponse(response);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public int doFirst(int workDone) {
        return sendNextMessageIfTime();
    }

    @Override
    public int doLast(int workDone) {
        return timer.execute(context.getClock(), EmberConstants.TIMER_TASK_LIMIT);
    }

    private int sendNextMessageIfTime() {
        int workDone = 0;
        if (isLeader && output != null) {
            final long nowNs = System.nanoTime();
            while (nowNs >= nextSendNs && workDone < MAX_MESSAGES_AT_ONCE) {
                sendNextMessage();
                workDone ++;

                nextSendNs = nextSendNs + sendIntervalNs;
                if (nowNs >= nextSendNs) {  // It's already time for next message ?
                    long skippedMessages = (nowNs - nextSendNs) / sendIntervalNs + 1;
                    nextSendNs = nextSendNs + skippedMessages * sendIntervalNs;
                }
            }
        }
        return workDone;
    }

    private void sendNextMessage() {
        final @Timestamp long now = context.getClock().time();
        final int symbolIndex = rnd.nextInt(symbols.length);
        final String symbol = symbols[symbolIndex];
        final InstrumentType instrumentType = instrumentTypes.get(symbols[symbolIndex], null);
        updateMessage.setSymbol(symbol);
        updateMessage.setInstrumentType(instrumentType);
        updateMessage.setTimeStampMs(now);
        updateMessage.setOriginalTimestamp(now);
        assert NUMBER_OF_ENTRIES_IN_UPDATE <= snapshotEntries.size();
        for (int i=0; i < 2; i++) {
            L2EntryNew srcEntry = (L2EntryNew) snapshotEntries.get(rnd.nextInt(snapshotEntries.size()));

            L2EntryUpdate dstEntry = (L2EntryUpdate) updateEntries.get(i);
            dstEntry.setSide(srcEntry.getSide());
            dstEntry.setLevel(srcEntry.getLevel());
            dstEntry.setPrice(srcEntry.getPrice());
            dstEntry.setSize(Decimal64Utils.fromInt(rnd.nextInt(MAX_ENTRY_SIZE) + 1));
        }
        if (validators != null)
            validators[symbolIndex].sendPackage(updateMessage);
        output.send(updateMessage);
    }

    private int snapshotCounter;

    private @Timestamp long sendSnapshotOnTimer (long now, Void parent) {
        sendMarketDataSnapshot ((snapshotCounter++) % symbols.length);
        return now + SNAPSHOT_INTERVAL_MILLIS/symbols.length;
    }

    private void sendAllMarketDataSnapshots () {
        for (int i=0; i < symbols.length; i++)
            sendMarketDataSnapshot (i);
    }

    private void sendMarketDataSnapshot(int symbolIndex) {
        final String symbol = symbols[symbolIndex];
        final InstrumentType instrumentType = instrumentTypes.get(symbols[symbolIndex], null);
        snapshotMessage.setSymbol(symbol);
        snapshotMessage.setInstrumentType(instrumentType);
        snapshotMessage.setTimeStampMs(context.getClock().time());
        if (validators != null)
            validators[symbolIndex].sendPackage(snapshotMessage);
        output.send(snapshotMessage);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onOrderPendingNewEvent(OrderPendingNewEvent event) {

    }

    @Override
    public void onOrderNewEvent(OrderNewEvent event) {

    }

    @Override
    public void onOrderRejectEvent(OrderRejectEvent event) {

    }

    @Override
    public void onOrderPendingCancelEvent(OrderPendingCancelEvent event) {

    }

    @Override
    public void onOrderCancelEvent(OrderCancelEvent event) {

    }

    @Override
    public void onOrderCancelRejectEvent(OrderCancelRejectEvent event) {

    }

    @Override
    public void onOrderPendingReplaceEvent(OrderPendingReplaceEvent event) {

    }

    @Override
    public void onOrderReplaceEvent(OrderReplaceEvent event) {

    }

    @Override
    public void onOrderReplaceRejectEvent(OrderReplaceRejectEvent event) {

    }

    @Override
    public void onOrderTradeReportEvent(OrderTradeReportEvent event) {

    }

    @Override
    public void onOrderTradeCancelEvent(OrderTradeCancelEvent event) {

    }

    @Override
    public void onOrderTradeCorrectEvent(OrderTradeCorrectEvent event) {

    }

    @Override
    public void onOrderStatusEvent(OrderStatusEvent event) {

    }

    @Override
    public void onOrderRestateEvent(OrderRestateEvent event) {

    }

    @Override
    public void onNewOrderRequest(OrderNewRequest request) {

    }

    @Override
    public void onReplaceOrderRequest(OrderReplaceRequest request) {

    }

    @Override
    public void onCancelOrderRequest(OrderCancelRequest request) {

    }

    @Override
    public void onOrderStatusRequest(OrderStatusRequest request) {

    }

    @Override
    public void onMassCancelOrderRequest(OrderMassCancelRequest request) {

    }

    @Override
    public void onMassOrderStatusRequest(OrderMassStatusRequest request) {

    }

    @Override
    public void onOrderListRequest(OrderListRequest request) {

    }


    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onBondUpdate(BondUpdate update) {
        rememberInstrumentType(update);
    }

    @Override
    public void onCfdUpdate(final CfdUpdate update) {
        rememberInstrumentType(update);
    }

    @Override
    public void onCurrencyUpdate(CurrencyUpdate update) {
        rememberInstrumentType(update);
    }

    @Override
    public void onCustomInstrumentUpdate(CustomInstrumentUpdate update) {
        rememberInstrumentType(update);
    }

    @Override
    public void onEquityUpdate(EquityUpdate update) {
        rememberInstrumentType(update);
    }

    @Override
    public void onEtfUpdate(EtfUpdate update) {
        rememberInstrumentType(update);
    }

    @Override
    public void onFutureUpdate(FutureUpdate update) {
        rememberInstrumentType(update);
    }

    @Override
    public void onIndexUpdate(IndexUpdate update) {
        rememberInstrumentType(update);
    }

    @Override
    public void onOptionUpdate(OptionUpdate update) {
        rememberInstrumentType(update);
    }

    @Override
    public void onSyntheticUpdate(SyntheticUpdate update) {
        rememberInstrumentType(update);
    }

    public void rememberInstrumentType(InstrumentUpdate update) {
        String symbol = getSupportedSymbol(update.getSymbol());
        if (symbol != null) {
            instrumentTypes.put(symbol, TradingUtils.convert(update.getInstrumentType()));
        }
    }

    private String getSupportedSymbol(CharSequence s) {
        for (int i=0; i < symbols.length; i++) {
            String symbol = symbols[i];
            if (CharSequenceUtil.equals(symbol, s))
                return symbol;
        }
        return null;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


    @Override
    public void onBinaryMessage(BinaryMessage msg) {

    }

    @Override
    public void onKillSwitchEvent(KillSwitchEvent event) {

    }

    @Override
    public void onMarketMessage(InstrumentMessage message) {

    }

    @Override
    public void onSessionStatusEvent(SessionStatusEvent event) {

    }

    @Override
    public void onPositionReport(PositionReport response) {

    }
}