package com.sachingupta.selfhealing.security.acl;

import org.springframework.stereotype.Component;

/** Enforces a {@link ToolAcl} at tool-invocation boundaries. */
@Component
public class AclGuard {

    public void enforce(ToolAcl acl, String toolName, String scope) {
        if (acl == null) {
            throw new AclDeniedException(
                    "no ACL configured for tool '" + toolName + "' in scope '" + scope + "'");
        }
        if (!acl.isAllowed(toolName, scope)) {
            throw new AclDeniedException(
                    "tool '"
                            + toolName
                            + "' not permitted in scope '"
                            + scope
                            + "' for this principal");
        }
    }
}
