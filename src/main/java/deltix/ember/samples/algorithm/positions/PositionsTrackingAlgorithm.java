package deltix.ember.service.algorithm.samples.positions;

import deltix.anvil.message.NodeStatus;
import deltix.anvil.message.NodeStatusEvent;
import deltix.ember.message.trade.oms.MutablePositionRequest;
import deltix.ember.message.trade.oms.PositionReport;
import deltix.ember.service.EmberConstants;
import deltix.ember.service.PositionRequestHandler;
import deltix.ember.service.algorithm.AlgorithmContext;
import deltix.ember.service.algorithm.SimpleAlgorithm;
import deltix.ember.service.oms.cache.OrdersCacheSettings;

public class PositionsTrackingAlgorithm extends SimpleAlgorithm {
    PositionsTrackingAlgorithm(AlgorithmContext context) {
        super(context);
    }

    @Override
    public void onNodeStatusEvent(NodeStatusEvent event) {
        super.onNodeStatusEvent(event);
        if (event.getNodeStatus() == NodeStatus.LEADER) {
            MutablePositionRequest request = new MutablePositionRequest();
            request.setRequestId(context.getRequestSequence().next());
            request.setSourceId(getId());
            request.setDestinationId(EmberConstants.EMBER_SOURCE_ID);
            request.setProjection("Source/Symbol");
            request.setSrc(getId()); //TODO?

            ((PositionRequestHandler)getOMS()).onPositionRequest(request);
        }
    }

    @Override
    public void onPositionReport(PositionReport response) {
        LOGGER.info(response.toString());
    }
}
