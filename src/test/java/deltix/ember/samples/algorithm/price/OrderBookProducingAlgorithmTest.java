package deltix.ember.samples.algorithm.price;

import com.epam.deltix.dfp.Decimal64Utils;
import deltix.data.stream.MessageChannel;
import deltix.ember.message.smd.InstrumentType;
import deltix.ember.service.algorithm.AbstractAlgorithm;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.SingleLegExecutionAlgoUnitTest;
import deltix.gflog.Log;
import deltix.gflog.LogEntry;
import deltix.gflog.LogFactory;
import deltix.gflog.LogLevel;
import deltix.qsrv.hf.pub.InstrumentMessage;
import deltix.quoteflow.orderbook.FullOrderBook;
import deltix.quoteflow.orderbook.OrderBookWaitingSnapshotMode;
import deltix.quoteflow.orderbook.interfaces.OrderBookLevel;
import deltix.quoteflow.orderbook.interfaces.OrderBookList;
import deltix.timebase.api.messages.DataModelType;
import deltix.timebase.api.messages.MarketMessageInfo;
import deltix.timebase.api.messages.universal.BaseEntryInfo;
import deltix.timebase.api.messages.universal.L2EntryNew;
import deltix.timebase.api.messages.universal.PackageHeader;
import org.junit.Assert;
import rtmath.containers.interfaces.LogProcessor;
import rtmath.containers.interfaces.Severity;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class MessageCountingFullOrderBook extends FullOrderBook {
    int messageCount = 0;

    MessageCountingFullOrderBook(String symbol) {
        super(symbol, OrderBookWaitingSnapshotMode.NOT_WAITING_FOR_SNAPSHOT, DataModelType.LEVEL_TWO);
    }

    @Override
    public void update(MarketMessageInfo message) {
        messageCount++;
        super.update(message);
    }
}

abstract public class OrderBookProducingAlgorithmTest<A extends AbstractAlgorithm> extends SingleLegExecutionAlgoUnitTest<A> {


    protected static final String OUTPUT_STREAM_KEY = "outputStream";
    private final MessageCountingFullOrderBook orderBook;
    private final List<PackageHeader> messages = new ArrayList<>();

    public OrderBookProducingAlgorithmTest(String outputSymbol, InstrumentType instrumentType) {
        super (outputSymbol, instrumentType);
        orderBook = new MessageCountingFullOrderBook(outputSymbol);
        orderBook.setLogger(new LogProcessorAdapter());
    }

    @SuppressWarnings("unchecked")
    protected AlgorithmContext getAlgorithmContext() {
        AlgorithmContext result = super.getAlgorithmContext();
        when(result.createOutputChannel(eq(OUTPUT_STREAM_KEY), any())).thenReturn(mockOutputChannel(orderBook, messages));
        return result;
    }


    protected void verifyNoOutputPrices() {
        Assert.assertTrue ("Not expecting any output prices at this point", messages.isEmpty());
    }

    protected void assertOutputPriceMessageCount(int expectedCount) {
        Assert.assertEquals("Expected number of output price messages", expectedCount, messages.size());
    }

    void trace(PackageHeader message) {
        for (BaseEntryInfo entry :  message.getEntries()) {
            if (entry instanceof L2EntryNew) {
                L2EntryNew e = (L2EntryNew) entry;
                System.out.println(e.getSide() + " " + Decimal64Utils.toString(e.getSize()) + " @ " + Decimal64Utils.toString(e.getPrice()));
            } else {
                System.out.println(entry);
            }
        }
    }

    private MessageChannel mockOutputChannel(final FullOrderBook orderBook, final List<PackageHeader> messages) {
        return new MessageChannel<InstrumentMessage>() {
            @Override
            public void send(InstrumentMessage msg) {
                try {
                    if (msg instanceof PackageHeader) {
                        PackageHeader p = (PackageHeader) msg;
                        if (true)
                            trace(p);
                        orderBook.update(p);
                        messages.add(p.clone());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    fail("Exception processing output market feed: " + e.getMessage());
                }
            }
            @Override
            public void close() {
            }
        };
    }

    ///============  book assertions ====================

    protected void assertBookEmpty () {
        assertTrue("There are unexpected offers in the book!", orderBook.getAllAskLevels().isEmpty());
        assertTrue("There are unexpected bids in the book!", orderBook.getAllBidLevels().isEmpty());
    }

    /**
     * Format:
     *  <pre>
     *    "7.001 @ 1440.99"
     *    "0.001 @ 1440.98"
     *    "0.062 @ 1440.97"
     *    "---------------"
     *   "10.941 @ 1440.95"
     *    "0.075 @ 1440.90"
     *    "0.003 @ 1440.89"
     * </pre>
     */
    protected void assertOrderBook(String expected) {
        String actual = printOrderBook(orderBook);
        assertEquals(expected, actual);
    }

    private static String printOrderBook(FullOrderBook orderBook) {
        StringBuilder sb = new StringBuilder();
        appendLevels(sb, orderBook.getAllAskLevels(), false);
        if (sb.length() > 0)
            sb.append('\n');
        sb.append("---------------");
        appendLevels(sb, orderBook.getAllBidLevels(), true);
        return sb.toString();
    }

    private static void appendLevels(StringBuilder sb, OrderBookList<OrderBookLevel> levels, boolean isBid) {
        int cnt = levels.size();
        if (isBid) {
            for (int i=0; i < cnt; i++)
                appendLevel(sb, levels.getObjectAt(i));
        } else {
            for (int i=cnt-1; i >= 0; i--)
                appendLevel(sb, levels.getObjectAt(i));
        }
    }

    private static void appendLevel(StringBuilder sb, OrderBookLevel level) {
        if (sb.length() > 0)
            sb.append('\n');
        sb.append(Decimal64Utils.toString(level.getSize()));
        sb.append(" @ ");
        sb.append(Decimal64Utils.toString(level.getPrice()));
    }
}


class LogProcessorAdapter implements LogProcessor {
    private final Log delegate;

    public LogProcessorAdapter() {
        this(LogFactory.getLog("deltix.ember.quoteflow"));
    }

    public LogProcessorAdapter(Log delegate) {
        this.delegate = delegate;
    }

    private static LogLevel severity2level(Severity severity) {
        switch (severity) {
            case ERROR:
                return LogLevel.ERROR;

            case WARNING:
                return LogLevel.WARN;

            case INFO:
                return LogLevel.INFO;

            default:
                return LogLevel.TRACE;
        }
    }

    @Override
    public void onLogEvent(Object sender, Severity severity, Throwable exception, CharSequence stringMessage) {
        LogEntry entry = delegate.log(severity2level(severity));
        try {
            entry.append(stringMessage);
            if (exception != null)
                entry.append(exception);
        } finally {
            entry.commit();
        }
    }
}
