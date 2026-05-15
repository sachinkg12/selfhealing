package com.sachingupta.selfhealing.security.acl;

import com.sachingupta.selfhealing.security.identity.AgentIdentity;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Default in-memory resolver. */
@Component
public class InMemoryPrincipalAclResolver implements PrincipalAclResolver {

    private final Map<String, ToolAcl> byPrincipal = new HashMap<>();

    @Override
    public ToolAcl resolve(AgentIdentity identity) {
        return byPrincipal.get(identity.name());
    }

    @Override
    public void install(AgentIdentity identity, ToolAcl acl) {
        byPrincipal.put(identity.name(), acl);
    }
}
