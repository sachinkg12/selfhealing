package com.sachingupta.selfhealing.scenarios;

import com.sachingupta.selfhealing.security.identity.AgentIdentity;
import com.sachingupta.selfhealing.security.identity.Role;
import java.util.Set;

/** Identity fixtures reused across scenarios. */
final class Identities {

    private Identities() {}

    static AgentIdentity triage() {
        return new AgentIdentity("triage-agent", Role.TRIAGE, Set.of("search"));
    }

    static AgentIdentity searchRemediator() {
        return new AgentIdentity("search-remediator", Role.REMEDIATOR, Set.of("search"));
    }

    static AgentIdentity authRemediatorOnly() {
        return new AgentIdentity("auth-remediator", Role.REMEDIATOR, Set.of("auth"));
    }
}
