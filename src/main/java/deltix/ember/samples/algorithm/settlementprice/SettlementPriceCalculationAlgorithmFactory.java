package deltix.ember.samples.algorithm.settlementprice;

import deltix.anvil.util.annotation.Optional;
import deltix.ember.service.algorithm.AbstractAlgorithmFactory;
import deltix.ember.service.algorithm.Algorithm;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.AlgorithmFactory;

public class SettlementPriceCalculationAlgorithmFactory extends AbstractAlgorithmFactory {

    /** Settlement prices stream key (main output) */
    @Optional
    private String settlementPricesStream = "settlementPrices";

    /** Time of day when observed prices will be written as settlement prices */
    @Optional
    private String publishTimeOfDay = "00:00:00";

    /** TimeZone for time of day */
    @Optional
    private String publishTimeZone = "America/New_York";

    /** When true prices will be written in canonical Universal Format, when false a simple trade message format will be used */
    @Optional
    private boolean publishSettlementAsStatisticsEntry;

    public void setSettlementPricesStream(String settlementPricesStream) {
        this.settlementPricesStream = settlementPricesStream;
    }

    public void setPublishTimeOfDay(String publishTimeOfDay) {
        this.publishTimeOfDay = publishTimeOfDay;
    }

    public void setPublishTimeZone(String publishTimeZone) {
        this.publishTimeZone = publishTimeZone;
    }

    public void setPublishSettlementAsStatisticsEntry(boolean publishSettlementAsStatisticsEntry) {
        this.publishSettlementAsStatisticsEntry = publishSettlementAsStatisticsEntry;
    }

    @Override
    public Algorithm create(AlgorithmContext context) {
        return new SettlementPriceCalculationAlgorithm (context, settlementPricesStream, publishTimeOfDay, publishTimeZone, publishSettlementAsStatisticsEntry);
    }
}
