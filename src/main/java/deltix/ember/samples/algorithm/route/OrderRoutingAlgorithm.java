package deltix.ember.samples.algorithm.route;

import deltix.anvil.util.Factory;
import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;
import deltix.ember.message.trade.CustomAttribute;
import deltix.ember.message.trade.MutableOrderNewRequest;
import deltix.ember.message.trade.OrderNewRequest;
import deltix.ember.service.algorithm.AlgoOrder;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.ChildOrder;
import deltix.ember.service.algorithm.SimplifiedAbstractAlgorithm;
import deltix.ember.service.oms.cache.OrdersCacheSettings;
import deltix.util.collections.generated.ObjectArrayList;

import java.util.List;


public class OrderRoutingAlgorithm extends SimplifiedAbstractAlgorithm<AlgoOrder> {

    private List<OrderRoutingCase> caseTable;

    private final ObjectArrayList<CustomAttribute> customAttributes = new ObjectArrayList<>(16);

    public OrderRoutingAlgorithm(AlgorithmContext context, OrdersCacheSettings cacheSettings, List<OrderRoutingCase> caseTable) {
        super(context, cacheSettings);

        this.caseTable = caseTable;
    }

    @Override
    protected Factory<AlgoOrder> createParentOrderFactory() {
        return AlgoOrder::new;
    }

    @Override
    protected Factory<ChildOrder<AlgoOrder>> createChildOrderFactory() {
        return ChildOrder::new;
    }

    @SuppressWarnings({"UseBulkOperation", "ForLoopReplaceableByForEach"})
    @Override
    protected void handleNewOrder(AlgoOrder order, OrderNewRequest request) {
        if (isLeader()) {
            OrderRoutingCase routingCase = (caseTable != null) ? find(caseTable, request.getQuantity()) : null;
            if (routingCase != null) {
                MutableOrderNewRequest childOrderRequest = makeChildOrderRequest(order);
                childOrderRequest.setDestinationId(routingCase.getDestination());

                List<CustomOrderAttribute> orderAttributes = routingCase.getAttributes();
                if (orderAttributes != null && ! orderAttributes.isEmpty()) {

                    ObjectArrayList<CustomAttribute> customAttributes = (ObjectArrayList<CustomAttribute>) childOrderRequest.getAttributes();
                    if (customAttributes == null) {
                        customAttributes = this.customAttributes;
                        customAttributes.clear();
                        childOrderRequest.setAttributes(customAttributes);
                    }
                    for (int i = 0; i < orderAttributes.size(); i++) {
                        customAttributes.add(orderAttributes.get(i));
                    }
                }
                submit(order, childOrderRequest);
            } else {
                sendRejectEvent(order, "Order doesn't match any supported cases");
            }
        }

    }


    private static OrderRoutingCase find(final List<OrderRoutingCase> caseTable, final @Decimal long quantity) {
        int low = 0;
        int high = caseTable.size() - 1;

        OrderRoutingCase result = null;
        while (low <= high) {
            int mid = (low + high) / 2;

            OrderRoutingCase cs = caseTable.get(mid);
            if (Decimal64Utils.isLess(cs.getQuantity(), quantity)) {
                result = cs;
                low = mid + 1;
            } else
            if (Decimal64Utils.isGreater(cs.getQuantity(), quantity)) {
                high = mid - 1;
            } else {
                assert Decimal64Utils.isEqual(cs.getQuantity(), quantity);
                result = cs;
                break;
            }

        }
        return result;
    }
}


