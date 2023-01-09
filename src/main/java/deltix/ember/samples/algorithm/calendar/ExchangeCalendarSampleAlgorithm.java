package deltix.ember.samples.algorithm.calendar;

import deltix.anvil.util.annotation.Timestamp;
import deltix.anvil.util.timer.OneTimerCallback;
import deltix.calendar.TradingCalendar;
import deltix.calendar.TradingSession;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.SimpleAlgorithm;
import deltix.ember.service.oms.cache.OrdersCacheSettings;

import java.util.concurrent.TimeUnit;

/**
 * Execution algorithm that shows how to track market session open/close events
 *
 * Look for
 */
public class ExchangeCalendarSampleAlgorithm extends SimpleAlgorithm {

    private final TradingCalendar [] exchangeCalendars;
    private final OneTimerCallback<TradingCalendar> marketOpenCallback = this::onMarketOpenTimer;
    private final OneTimerCallback<TradingCalendar> marketCloseCallback = this::onMarketCloseTimer;
    private final OneTimerCallback<Void> calendarsRefreshCallback = this::onCalendarRefreshTimer;

    ExchangeCalendarSampleAlgorithm(AlgorithmContext context, OrdersCacheSettings cacheSettings) {
        super(context, cacheSettings);

        this.exchangeCalendars = initCalendars("CBOE/Futures", "CBOT/Grains");
    }

    private TradingCalendar [] initCalendars(String... exchangeCalendarCodes) {
        TradingCalendar [] result = new TradingCalendar[exchangeCalendarCodes.length];
        for (int i=0; i < result.length; i++) {
            String exchangeCalendarCode = exchangeCalendarCodes[i];
            result[i] = context.getTradingCalendar(exchangeCalendarCode);
            if (result[i] == null) {
                LOGGER.error("Calendar for exchange \"%s\" is not found").with(exchangeCalendarCode);
            }
        }
        return result;
    }

    private void onCalendarRefreshTimer(@Timestamp long currentTime, Void none) {
        for (int i=0; i < exchangeCalendars.length; i++) {
            TradingCalendar exchangeCalendar = exchangeCalendars[i];
            if (exchangeCalendar != null) {
                exchangeCalendars[i] = context.getTradingCalendar(exchangeCalendar.getCalendarCode());
            }
        }
    }

    @Override
    public void open() {
        super.open();
        scheduleMarketOpenCloseEvents();
        scheduleCalendarRefresh();
    }

    /** Make sure we refresh calendars periodically */
    private void scheduleCalendarRefresh() {
        getTimer().schedule(getClock().time() + TimeUnit.DAYS.toMillis(7), calendarsRefreshCallback, null);
    }

    private void scheduleMarketOpenCloseEvents() {
        @Timestamp long now = context.getClock().time();
        for (int i=0; i < exchangeCalendars.length; i++) {
            TradingCalendar exchangeCalendar = exchangeCalendars[i];
            if (exchangeCalendar != null)
                scheduleNextMarketOpenCloseEvent(now, exchangeCalendar);

        }
    }

    private void scheduleNextMarketOpenCloseEvent(@Timestamp long currentTime, TradingCalendar exchangeCalendar) {
        try {
            TradingSession session = exchangeCalendar.getCurrentOrNextTradingSession(currentTime).getTradingSession();
            assert session != null;

            @Timestamp long marketOpen = session.getStartMilli();
            @Timestamp long marketClose = session.getEndMilli();
            if (currentTime < marketOpen) {
                getTimer().schedule(marketOpen, marketOpenCallback, exchangeCalendar);
            } else {
                assert currentTime < marketClose;
                getTimer().schedule(marketClose, marketCloseCallback, exchangeCalendar);
            }

        } catch (Exception e) {
            LOGGER.error("Error calculating next trading session for exchange \"%s\": %s").with(exchangeCalendar.getCalendarCode()).with(e.getMessage());
        }
    }

    private void onMarketOpenTimer(@Timestamp long currentTime, TradingCalendar tradingCalendar) {
        onMarketOpen(tradingCalendar.getCalendarCode());
        scheduleNextMarketOpenCloseEvent(currentTime, tradingCalendar);
    }

    private void onMarketCloseTimer(@Timestamp long currentTime, TradingCalendar tradingCalendar) {
        onMarketClose(tradingCalendar.getCalendarCode());
        scheduleNextMarketOpenCloseEvent(currentTime, tradingCalendar);
    }

    protected void onMarketOpen(String exchangeCalendarCode) {
        LOGGER.info("Market is open for %s").with(exchangeCalendarCode);
        //TODO: Put your code here
    }

    protected void onMarketClose(String exchangeCalendarCode) {
        LOGGER.info("Market is closed for %s").with(exchangeCalendarCode);
        //TODO: Put your code here
    }


}
