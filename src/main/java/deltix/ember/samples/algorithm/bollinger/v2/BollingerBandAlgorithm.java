package deltix.ember.samples.algorithm.bollinger.v2;


import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.anvil.util.AsciiStringBuilder;
import deltix.anvil.util.Factory;
import deltix.anvil.util.annotation.Alphanumeric;
import deltix.ember.message.trade.*;
import deltix.ember.samples.algorithm.bollinger.BollingerBandSettings;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.md.InstrumentDataFactory;
import deltix.ember.service.algorithm.v2.AbstractL1TradingAlgorithm;
import deltix.ember.service.algorithm.v2.OutboundOrderProcessorImpl;
import deltix.ember.service.algorithm.v2.order.OrderEntryReq;
import deltix.ember.service.algorithm.v2.order.OutboundOrder;
import deltix.ember.service.oms.cache.OrdersCacheSettings;
import deltix.timebase.api.messages.universal.TradeEntry;
import deltix.util.collections.generated.ObjectArrayList;


/**
 *  Bollinger Band trading strategy.
 *  See <a href="http://www.investopedia.com/terms/b/bollingerbands.asp">http://www.investopedia.com/terms/b/bollingerbands.asp</a>
 */
public class BollingerBandAlgorithm extends AbstractL1TradingAlgorithm<BollingerL1InstrumentState, OutboundOrder> {
    private final BollingerBandSettings bandSettings;
    private final AsciiStringBuilder orderReason = new AsciiStringBuilder(64);
    private final ObjectArrayList<CustomAttribute> orderAttributes = new ObjectArrayList<>(1);

    public BollingerBandAlgorithm(AlgorithmContext context, BollingerBandSettings bandSettings) {
        super(context);
        this.bandSettings = bandSettings;
    }

    /// region Market data

    @Override
    protected InstrumentDataFactory<BollingerL1InstrumentState> createInstrumentDataFactory() {
        return (symbol, instrumentType) -> new BollingerL1InstrumentState(symbol, instrumentType, bandSettings.getNumPeriods()) {

            @Override
            protected void onTradeMessage(TradeEntry message) {
                super.onTradeMessage(message);

                smav.register(message.getPrice());

                if (smav.isFull()) {
                    if (pendingSize == 0) { // only if we do not have active orders

                        MutableOrderNewRequest orderRequest = orderProcessor.makeSubmitRequest();
                        AsciiStringBuilder reason = prepareOrderReason(orderRequest);

                        double orderSize = checkBollingerBands(message, reason);
                        if (orderSize != 0) {
                            prepareNewOrderRequest(orderRequest, getSymbol(), Decimal64Utils.fromDouble(orderSize), message.getPrice(), message.getExchangeId());
                            submit(orderRequest);
                            LOGGER.info("Sent %s %s %s @ %s Reason:%s")
                                    .with(orderRequest.getSide())
                                    .with(orderRequest.getSymbol())
                                    .withDecimal64(orderRequest.getQuantity())
                                    .withDecimal64(orderRequest.getLimitPrice())
                                    .with(reason);

                            this.pendingSize = orderSize;
                        }
                    }
                }
            }

            private double checkBollingerBands(TradeEntry trade, AsciiStringBuilder reason) {
                final double mp = smav.getAverage();
                final double sqrtVariance = smav.getStdDev();
                final double upperBand = mp + bandSettings.getNumStdDevs() * sqrtVariance;
                final double bottomBand = mp - bandSettings.getNumStdDevs() * sqrtVariance;

                // rules for position opening
                double price = Decimal64Utils.toDouble(trade.getPrice());
                double result = 0;
                if (positionSize == 0) {
                    double tradeSize = Decimal64Utils.toDouble(trade.getSize());
                    if (price > upperBand && bandSettings.isEnableShort()) {
                        result = - Math.max(1, (int) (tradeSize * bandSettings.getOpenOrderSizeCoefficient()));
                        reason.append("Above ").append(upperBand);
                    } else if (price < bottomBand && bandSettings.isEnableLong()) {
                        result = Math.max(1, (int) (tradeSize * bandSettings.getOpenOrderSizeCoefficient()));
                        reason.append("Below ").append(bottomBand);
                    }
                } else {
                    // risk management - check for stop loss
                    double pnlPercent = getProfitRatio(price) * 100;

                    if (pnlPercent < -bandSettings.getStopLossPercent()) {
                        reason.append("Stop Loss: ").append(pnlPercent).append("%");
                        result = -positionSize;
                    } else if (pnlPercent > bandSettings.getProfitPercent()) { // check for profit taking
                        reason.append("Profit Taking: ").append(pnlPercent).append("%");
                        result = -positionSize;
                    } else if (positionSize < 0 && price < mp) {// rules for position closing
                        reason.append("Close Short (MP = ").append(mp).append(")");
                        result = -positionSize;
                    } else if (positionSize > 0 && price > mp) {
                        reason.append("Close Long (MP = ").append(mp).append(")");
                        result = -positionSize;
                    }
                }
                return result;
            }

        };
    }

    private AsciiStringBuilder prepareOrderReason(MutableOrderNewRequest orderRequest) {
        orderRequest.setAttributes(orderAttributes);
        orderReason.clear();
        return orderReason; // don't leave it empty
    }

    /// endregion

    /// region Trading

    @Override
    protected Factory<OutboundOrder> createOrderFactory() {
        return OutboundOrder::new;
    }

    @Override
    protected OutboundOrderProcessorImpl<OutboundOrder> createOutboundOrderProcessor(AlgorithmContext context, OrdersCacheSettings cacheSettings) {
        return new BollingerOrderProcessor(context, cacheSettings, createOrderFactory(), createOrderEntryReqFactory());
    }

    class BollingerOrderProcessor extends OutboundOrderProcessorImpl<OutboundOrder> {

        public BollingerOrderProcessor(AlgorithmContext context, OrdersCacheSettings cacheSettings, Factory<OutboundOrder> orderFactory, Factory<OrderEntryReq> entryReqFactory) {
            super(context, cacheSettings, orderFactory, entryReqFactory);
        }

        /** Process fill events of our orders */
        @Override
        public void onTradeReport(OutboundOrder order, OrderTradeReportEvent event) {
            super.onTradeReport(order, event);

            BollingerL1InstrumentState instrumentInfo = get(order.getSymbol());
            if (instrumentInfo != null) {
                final double cost = Decimal64Utils.toDouble(event.getTradeQuantity()) * bandSettings.getCommissionPerShare();

                if (order.getSide() == Side.BUY) {
                    instrumentInfo.positionSize += Decimal64Utils.toDouble(event.getTradeQuantity());
                    instrumentInfo.positionCash -= Decimal64Utils.toDouble(Decimal64Utils.multiply(event.getTradeQuantity(), event.getTradePrice()));
                } else {
                    instrumentInfo.positionSize -= Decimal64Utils.toDouble(event.getTradeQuantity());
                    instrumentInfo.positionCash += Decimal64Utils.toDouble(Decimal64Utils.multiply(event.getTradeQuantity(), event.getTradePrice()));
                }
                instrumentInfo.positionCost += cost;


                if (instrumentInfo.positionSize == 0) {
                    //outputMessage.totalProfit += instrumentInfo.positionCash;
                    instrumentInfo.positionCash = 0;
                }

                LOGGER.info("Size of %s position changed to %s (position cash %s)")
                        .with(instrumentInfo.getSymbol())
                        .with(instrumentInfo.positionSize)
                        .with(instrumentInfo.positionCash);

                if (order.isFinal())
                    instrumentInfo.pendingSize = 0;

                //TODO: positionsChannel.send(instrumentInfo);
            }
        }
    }

    private void prepareNewOrderRequest (MutableOrderNewRequest orderRequest, CharSequence symbol, @Decimal long orderSize, @Decimal long limitPrice, @Alphanumeric long exchangeCode) {
        orderRequest.setQuantity(Decimal64Utils.abs(orderSize));
        orderRequest.setSide(Decimal64Utils.isPositive(orderSize) ? Side.BUY : Side.SELL);
        orderRequest.setLimitPrice(limitPrice);
        orderRequest.setSymbol(symbol);
        orderRequest.setOrderType(OrderType.LIMIT);
        orderRequest.setExchangeId(exchangeCode);
        //orderRequest.setDestinationId(destinationId);
    }

    /// endregion

}
