package deltix.ember.samples.algorithm.bollinger.v1;

import com.epam.deltix.dfp.Decimal64Utils;
import deltix.anvil.util.AsciiStringBuilder;
import deltix.anvil.util.Factory;
import deltix.anvil.util.codec.AlphanumericCodec;
import deltix.data.stream.MessageChannel;
import deltix.efix.message.field.Tag;
import deltix.ember.message.trade.*;
import deltix.ember.samples.algorithm.bollinger.BollingerBandSettings;
import deltix.ember.service.algorithm.AbstractAlgorithm;
import deltix.ember.service.algorithm.AlgoOrder;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.ChildOrder;
import deltix.ember.service.algorithm.md.InstrumentDataFactory;
import deltix.ember.service.oms.cache.OrdersCacheSettings;
import deltix.qsrv.hf.pub.InstrumentMessage;
import deltix.timebase.api.messages.TradeMessageInfo;
import deltix.util.collections.generated.ObjectArrayList;

/**
 *  Bollinger Band trading strategy.
 *
 *  This is original version of this sample that uses version 1 of algorithm framework. Consider using version 2.
 *
 *  See <a href="http://www.investopedia.com/terms/b/bollingerbands.asp">http://www.investopedia.com/terms/b/bollingerbands.asp</a>
 *
 */
class BollingerBandAlgorithm extends AbstractAlgorithm<AlgoOrder, BollingerBandInstrumentData> {

    private final AsciiStringBuilder orderReason = new AsciiStringBuilder(64);
    private final ObjectArrayList<CustomAttribute> orderAttributes = new ObjectArrayList<>(1);
    private final long destinationId;
    private final BollingerBandSettings bandSettings;

    private final MessageChannel positionsChannel;

    BollingerBandAlgorithm(AlgorithmContext context, OrdersCacheSettings cacheSettings, BollingerBandSettings bandSettings) {
        super(context, cacheSettings);

        this.bandSettings = bandSettings;

        MutableCustomAttribute attr = new MutableCustomAttribute();
        attr.setKey(Tag.Text);
        attr.setValue(orderReason);
        orderAttributes.add(attr);

        positionsChannel = context.createOutputChannel("bollinger-positions", BollingerBandInstrumentMessage.class);

        destinationId = AlphanumericCodec.encode("SIM");
    }

    @Override
    public void close() {
        positionsChannel.close();
    }

    @Override
    protected Factory<AlgoOrder> createParentOrderFactory() {
        return AlgoOrder::new;
    }

    @Override
    protected Factory<ChildOrder<AlgoOrder>> createChildOrderFactory() {
        return ChildOrder::new;
    }

    /** Process fill events of our orders */
    @Override
    @SuppressWarnings("unchecked")
    protected void handleTradeEvent(ChildOrder<AlgoOrder> order, OrderTradeReportEvent event) {
        BollingerBandInstrumentData instrumentInfo = get(order.getSymbol());
        if (instrumentInfo != null) {
            final double cost = Decimal64Utils.toDouble(event.getTradeQuantity()) * bandSettings.getCommissionPerShare();

            if (order.getSide() == Side.BUY) {
                instrumentInfo.data.positionSize += Decimal64Utils.toLong(event.getTradeQuantity());
                instrumentInfo.data.positionCash -= Decimal64Utils.toDouble(Decimal64Utils.multiply(event.getTradeQuantity(), event.getTradePrice()));
            } else {
                instrumentInfo.data.positionSize -= Decimal64Utils.toLong(event.getTradeQuantity());
                instrumentInfo.data.positionCash += Decimal64Utils.toDouble(Decimal64Utils.multiply(event.getTradeQuantity(), event.getTradePrice()));
            }
            instrumentInfo.data.positionCost += cost;


            if (instrumentInfo.data.positionSize == 0) {
                //outputMessage.totalProfit += instrumentInfo.positionCash;
                instrumentInfo.data.positionCash = 0;
            }

            LOGGER.info("Size of %s position changed to %s (position cash %s)")
                    .with(instrumentInfo.getSymbol())
                    .with(instrumentInfo.data.positionSize)
                    .with(instrumentInfo.data.positionCash);

            if (order.isFinal())
                instrumentInfo.data.pendingSize = 0;

            positionsChannel.send(instrumentInfo);
        }
    }

    /** Process market data: trade events */
    @Override
    protected InstrumentDataFactory<BollingerBandInstrumentData> createInstrumentDataFactory() {
        return (symbol, instrumentType) -> new BollingerBandInstrumentData(symbol, instrumentType, bandSettings.getNumPeriods()) {

            @Override
            public void onMarketMessage(InstrumentMessage message) {
                if (isLeader() && (message instanceof TradeMessageInfo)) {
                    TradeMessageInfo trade = (TradeMessageInfo) message;
                    final double price = trade.getPrice();
                    if (addToSMA(price)) {
                        if (this.data.pendingSize == 0) { // only if we do not have active orders
                            MutableOrderNewRequest orderRequest = tradingMessages.getNewOrderRequest();
                            AsciiStringBuilder reason = prepareOrderReason(orderRequest);

                            long tradeSize = checkBollingerBands(trade, price, reason);
                            if (tradeSize != 0) {
                                prepareNewOrderRequest(orderRequest, tradeSize, price, trade.getExchangeId());
                                submit(orderRequest);
                                LOGGER.info("Sent %s %s %s @ %s Reason:%s")
                                        .with(orderRequest.getSide())
                                        .with(orderRequest.getSymbol())
                                        .with(Decimal64Utils.toLong(orderRequest.getQuantity()))
                                        .withDecimal64(orderRequest.getLimitPrice())
                                        .with(reason);

                                this.data.pendingSize = tradeSize;
                            }
                        }
                    }
                }
            }

            private long checkBollingerBands(TradeMessageInfo trade, double price, AsciiStringBuilder reason) {
                long tradeSize = 0;
                final double mp = smav.getAverage();
                final double sqrtVariance = smav.getStdDev();
                final double upperBand = mp + bandSettings.getNumStdDevs() * sqrtVariance;
                final double bottomBand = mp - bandSettings.getNumStdDevs() * sqrtVariance;

                // rules for position opening
                if (data.positionSize == 0) {
                    if (price > upperBand && bandSettings.isEnableShort()) {
                        tradeSize = - Math.max(1, (int) (trade.getSize() * bandSettings.getOpenOrderSizeCoefficient()));
                        reason.append("Above ").append(upperBand);
                    } else if (price < bottomBand && bandSettings.isEnableLong()) {
                        tradeSize = Math.max(1, (int) (trade.getSize() * bandSettings.getOpenOrderSizeCoefficient()));
                        reason.append("Below ").append(bottomBand);
                    }
                } else {
                    // risk management - check for stop loss
                    double pnlPercent = getProfitRatio(price) * 100;

                    if (pnlPercent < -bandSettings.getStopLossPercent()) {
                        reason.append("Stop Loss: ").append(pnlPercent).append("%");
                        tradeSize = -data.positionSize;
                    } else if (pnlPercent > bandSettings.getProfitPercent()) { // check for profit taking
                        reason.append("Profit Taking: ").append(pnlPercent).append("%");
                        tradeSize = -data.positionSize;
                    } else if (data.positionSize < 0 && price < mp) {// rules for position closing
                        reason.append("Close Short (MP = ").append(mp).append(")");
                        tradeSize = -data.positionSize;
                    } else if (data.positionSize > 0 && price > mp) {
                        reason.append("Close Long (MP = ").append(mp).append(")");
                        tradeSize = -data.positionSize;
                    }
                }
                return tradeSize;
            }

            private void prepareNewOrderRequest (MutableOrderNewRequest orderRequest, long tradeSize, double price, long exchangeCode) {
                orderRequest.setOrderId(generateOrderId());
                orderRequest.setQuantity(Decimal64Utils.fromLong(Math.abs(tradeSize)));
                orderRequest.setSide(tradeSize > 0 ? Side.BUY : Side.SELL);
                orderRequest.setLimitPrice(Decimal64Utils.fromDouble(price));
                orderRequest.setSymbol(symbol);
                orderRequest.setOrderType(OrderType.LIMIT);
                orderRequest.setSourceId(getId());
                orderRequest.setDestinationId(destinationId);
                orderRequest.setExchangeId(exchangeCode);
                orderRequest.setTimestamp(getClock().time());
            }
        };
    }

    private AsciiStringBuilder prepareOrderReason(MutableOrderNewRequest orderRequest) {
        orderRequest.setAttributes(orderAttributes);
        orderReason.clear();
        return orderReason; // don't leave it empty
    }

}

