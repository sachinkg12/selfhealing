package com.sachingupta.selfhealing.advisor.confidence;

import com.sachingupta.selfhealing.advisor.api.ReasoningContext;

/**
 * Strategy (Strategy pattern) for turning a {@link ReasoningContext} into a confidence score in [0,
 * 1]. New scoring methods plug in as new strategies (Open/Closed).
 */
public interface ConfidenceStrategy {

    double score(ReasoningContext context);
}
