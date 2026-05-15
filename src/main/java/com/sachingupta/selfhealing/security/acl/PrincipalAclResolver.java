package com.sachingupta.selfhealing.security.acl;

import com.sachingupta.selfhealing.security.identity.AgentIdentity;

/**
 * Resolves the {@link ToolAcl} attached to a principal. In a real deployment this would call a
 * policy service; here it is in-memory so scenarios can wire their own policy.
 */
public interface PrincipalAclResolver {

    ToolAcl resolve(AgentIdentity identity);

    void install(AgentIdentity identity, ToolAcl acl);
}
