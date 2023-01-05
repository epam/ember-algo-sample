package deltix.ember.samples.algorithm.route;

import deltix.anvil.util.annotation.Alphanumeric;
import com.epam.deltix.dfp.Decimal;
import deltix.ember.service.algorithm.AbstractAlgorithmFactory;
import deltix.ember.service.algorithm.AlgorithmContext;

import java.util.ArrayList;
import java.util.List;

public class OrderRoutingAlgorithmFactory extends AbstractAlgorithmFactory {

    private List<String> caseTable;

    @Alphanumeric
    private long testAlphanumeric;

    @Decimal
    private long testDecimal;

    public void setCaseTable(List<String> caseTable) {
        this.caseTable = caseTable;
    }

    public void setTestAlphanumeric(@Alphanumeric long testAlphanumeric) {
        this.testAlphanumeric = testAlphanumeric;
    }

    public void setTestDecimal(@Decimal long testDecimal) {
        this.testDecimal = testDecimal;
    }

    @Override
    public OrderRoutingAlgorithm create(AlgorithmContext context) {
        List<OrderRoutingCase> ct =  new ArrayList<>();
        for (String caseTableItem : caseTable)
            ct.add(new OrderRoutingCase(caseTableItem));

        return new OrderRoutingAlgorithm(context, getCacheSettings(), ct);
    }
}
