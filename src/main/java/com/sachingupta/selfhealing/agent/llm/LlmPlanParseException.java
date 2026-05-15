package com.sachingupta.selfhealing.agent.llm;

/** Thrown when the LLM output cannot be parsed into a valid planned step. */
public class LlmPlanParseException extends RuntimeException {
    public LlmPlanParseException(String message) {
        super(message);
    }
}
