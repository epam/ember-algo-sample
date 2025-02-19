package deltix.ember.samples.algorithm.t2o;

import deltix.anvil.util.codec.AlphanumericCodec;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.ember.message.trade.MutableOrderNewRequest;
import deltix.ember.message.trade.Side;
import deltix.ember.message.trade.TimeInForce;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.SimpleAlgorithm;
import deltix.ember.service.oms.TradingUtils;
import deltix.ember.service.oms.cache.OrdersCacheSettings;
import deltix.qsrv.hf.pub.InstrumentMessage;
import deltix.qsrv.hf.pub.InstrumentType;
import deltix.timebase.api.messages.BestBidOfferMessage;
import deltix.timebase.api.messages.L2Message;
import deltix.timebase.api.messages.Level2ActionInfo;
import deltix.timebase.api.messages.universal.BaseEntryInfo;
import deltix.timebase.api.messages.universal.BasePriceEntryInfo;
import deltix.timebase.api.messages.universal.PackageHeader;

import java.time.Duration;

import static deltix.anvil.util.timer.TimerCallback.DO_NOT_RESCHEDULE;

public class TickToOrderAlgorithm extends SimpleAlgorithm {

    private final int inOutRatio;
    private int messageCount;
    private boolean startSubmission;
    private final long destinationConnectorId;
    private final long destinationExchangeId;

    TickToOrderAlgorithm(AlgorithmContext context, OrdersCacheSettings cacheSettings, int inOutRatio, Duration submissionDelay, String destinationConnectorName, String destinationExchange) {
        super(context, cacheSettings);
        this.inOutRatio = inOutRatio;
        this.destinationConnectorId = AlphanumericCodec.encode(destinationConnectorName);
        this.destinationExchangeId = AlphanumericCodec.encode(destinationExchange);

        LOGGER.info("Tick to order algorithm will use in/out ratio %s").with(inOutRatio);

        getTimer().schedule(currentTime() + submissionDelay.toMillis(),
            (now, cooke) -> {
                startSubmission = true;
                return DO_NOT_RESCHEDULE;
            }, null);
    }

    @Override
    public void onMarketMessage(final InstrumentMessage message) {
        super.onMarketMessage(message);
        if (message instanceof PackageHeader) {
            onPackageHeader(((PackageHeader) message));
        } else if (message instanceof L2Message) {
            onL2Message(((L2Message) message));
        } else if (message instanceof BestBidOfferMessage) {
            onBBOMessage(((BestBidOfferMessage) message));
        }
    }

    private void onBBOMessage(BestBidOfferMessage message) {
        if (message.hasOfferPrice())
            onSignal (true, message.getOfferSize(), message.getSymbol(), message.getInstrumentType(), message.getOfferPrice(), null);
        else
            onSignal (false, message.getBidSize(), message.getSymbol(), message.getInstrumentType(), message.getBidPrice(), null);
    }

    private void onL2Message(L2Message message) {
        if (message.hasActions()) {
            Level2ActionInfo action = message.getActions().get(0);
            onSignal (action.isAsk(), action.getSize(), message.getSymbol(), message.getInstrumentType(), action.getPrice(), action.getQuoteId());
        }
    }

    private void onPackageHeader(PackageHeader message) {
        if (message.hasEntries() && ! message.getEntries().isEmpty()) {
            BaseEntryInfo entry = message.getEntries().get(0);
//            if (entry instanceof TradeEntryInfo) {
//                TradeEntryInfo trade = (TradeEntryInfo) entry;
//                onSignal(trade.getSide() == AggressorSide.SELL, trade.getSize(), message.getSymbol(), message.getInstrumentType(), trade.getPrice(), trade.getMatchId());
//            } else
            if (entry instanceof BasePriceEntryInfo) {
                BasePriceEntryInfo level = (BasePriceEntryInfo) entry;
                onSignal(true, level.getSize(), message.getSymbol(), message.getInstrumentType(), level.getPrice(), level.getQuoteId());
            }
        }
    }

    private void onSignal(boolean isAsk, double size, CharSequence symbol, InstrumentType instrumentType, double price, CharSequence quoteId) {
        if (isLeader() && startSubmission) {
            if (inOutRatio == 1 || (messageCount++) % inOutRatio == 0) {
                MutableOrderNewRequest order = makeLimitOrder(isAsk ? Side.BUY : Side.SELL, Decimal64Utils.fromDouble(size), symbol, Decimal64Utils.fromDouble(price));
                order.setInstrumentType(TradingUtils.convert(instrumentType, false));
                order.setDestinationId(destinationConnectorId);
                order.setExchangeId(destinationExchangeId);
                order.setTimeInForce(TimeInForce.IMMEDIATE_OR_CANCEL);
                order.setQuoteId(quoteId); // Tick-to-Order Correlation ID

                submit(order);
            }
        }
    }
}
