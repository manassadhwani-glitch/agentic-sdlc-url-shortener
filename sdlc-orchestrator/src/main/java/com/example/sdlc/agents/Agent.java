package com.example.sdlc.agents;

/**
 * A specialist that does the work <i>inside</i> one stage. Agents are stateless and side-effect free: they read
 * only the inputs the stage declares, and they <b>propose</b> outputs and file changes. The engine decides what
 * happens next: policy review, approvals, applying changes, gates and retries.
 */
public interface Agent {

    String name();

    AgentResult run(AgentContext ctx) throws Exception;
}
