package cc.hifly.xrayandroid.parser;

import cc.hifly.xrayandroid.model.NodeRecord;

import java.util.ArrayList;
import java.util.List;

public class ParseBatch {
    public final List<NodeRecord> nodes = new ArrayList<>();
    public final List<String> skippedSamples = new ArrayList<>();
    public int skippedLineCount;
}
