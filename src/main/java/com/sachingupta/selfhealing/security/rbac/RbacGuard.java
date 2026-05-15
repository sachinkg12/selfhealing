package com.sachingupta.selfhealing.security.rbac;

import com.sachingupta.selfhealing.security.identity.AgentIdentity;
import com.sachingupta.selfhealing.security.identity.Role;
import org.springframework.stereotype.Component;

/**
 * Guard (specification-style) that enforces the coarse RBAC rules of Section III-C: read tools are
 * open to both roles, write tools are limited to {@link Role#REMEDIATOR} and to identities that
 * hold the target scope.
 */
@Component
public class RbacGuard {

    public void authorizeRead(AgentIdentity identity) {
        // Both roles can read; identity presence is sufficient.
        if (identity == null) {
            throw new RbacDeniedException("no agent identity on request");
        }
    }

    public void authorizeWrite(AgentIdentity identity, String scope) {
        if (identity == null) {
            throw new RbacDeniedException("no agent identity on request");
        }
        if (identity.role() != Role.REMEDIATOR) {
            throw new RbacDeniedException(
                    "role " + identity.role() + " cannot call write tools; only REMEDIATOR may");
        }
        if (!identity.hasScope(scope)) {
            throw new RbacDeniedException(
                    "remediator "
                            + identity.name()
                            + " not authorized for scope '"
                            + scope
                            + "'; authorized scopes: "
                            + identity.scopes());
        }
    }
}
