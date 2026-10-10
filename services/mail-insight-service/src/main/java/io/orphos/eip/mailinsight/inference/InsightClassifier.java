package io.orphos.eip.mailinsight.inference;

import io.orphos.eip.mailinsight.graph.GraphItem;

/** Strategy for turning a normalized mail/calendar item into a {@link Classification}. */
public interface InsightClassifier {
    Classification classify(GraphItem item);
}
