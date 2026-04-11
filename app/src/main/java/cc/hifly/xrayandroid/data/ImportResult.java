package cc.hifly.xrayandroid.data;

import cc.hifly.xrayandroid.model.SubscriptionRecord;

public class ImportResult {
    public final SubscriptionRecord subscription;
    public final int addedNodeCount;
    public final int updatedNodeCount;
    public final int skippedLineCount;
    public final int parsedNodeCount;

    public ImportResult(
            SubscriptionRecord subscription,
            int addedNodeCount,
            int updatedNodeCount,
            int skippedLineCount,
            int parsedNodeCount
    ) {
        this.subscription = subscription;
        this.addedNodeCount = addedNodeCount;
        this.updatedNodeCount = updatedNodeCount;
        this.skippedLineCount = skippedLineCount;
        this.parsedNodeCount = parsedNodeCount;
    }
}
