package com.sachingupta.selfhealing.agent;

/**
 * Spring AI-style abstraction over the LLM call. Kept as a one-method interface so a real
 * implementation (Spring AI {@code ChatClient}) can be swapped in without touching the agent. The
 * demo wires {@link DeterministicChatClient}, which never calls an LLM.
 */
public interface ChatClient {

    String describe();
}
