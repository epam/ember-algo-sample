package deltix.ember.samples.algorithm.iceberg;

import deltix.anvil.util.annotation.Alphanumeric;
import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.ember.message.trade.MutableOrderEntryRequest;
import deltix.ember.service.algorithm.AlgoOrder;
import deltix.ember.service.algorithm.fuzzy.AlgorithmFuzzyTest;
import deltix.ember.service.algorithm.fuzzy.SimpleAlgorithmFuzzyTest;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;


public class Test_IcebergAlgorithmFuzzy extends AlgorithmFuzzyTest<IcebergAlgorithm> {

    @Override
    protected IcebergAlgorithm createAlgorithm() {
        @Alphanumeric long defaultDestination = EXCHANGE_ID;
        return new IcebergAlgorithm(getAlgorithmContext(), getCacheSettings(), EXCHANGE_ID);
    }


    @Test
    public void test () {
        new ExperimentBuilder()
                .withSymbols("APPL", "NFLX", "AMZN", "GOOG", "MSFT")
                .add (new TimeActivity(Duration.ofMillis(100)), 10)
                .add (new UserActivity(), 1.5)
                .add (new MarketFeedActivity(), 7)
                .add (new MarketExchangeActivity())
                .run (1000000);
    }

    @Override
    protected void randomize(MutableOrderEntryRequest request) {
        super.randomize(request);
        request.setLimitPrice(randomPrice());
        if (rnd.nextBoolean())
            request.setDisplayQuantity(randomOrderSize());
        //setAttribute(request, IcebergOrderParameters.SHOW_ORDER_ATTRIBUTE_KEY, rnd.nextBoolean() ? "Y" : "N");
        //setAttribute(request, IcebergOrderParameters.DURATION_ATTRIBUTE_KEY, String.format("%ds", rnd.nextInt(MAX_ORDER_DURATION) + 1));
    }

    @Override
    protected void checkInvariants(AlgoOrder algoOrder) {
        super.checkInvariants(algoOrder);

        @Decimal long workingQuantity = algoOrder.getRemainingChildrenQuantity();
        @Decimal long displayQuantity = algoOrder.getWorkingOrder().getDisplayQuantity();
        if (!Decimal64Utils.isNaN(displayQuantity)) {
            Assert.assertTrue("Working " + Decimal64Utils.toString(workingQuantity) + " <= Display " + Decimal64Utils.toString(displayQuantity), Decimal64Utils.isLessOrEqual(workingQuantity, displayQuantity));
        }
    }
}
