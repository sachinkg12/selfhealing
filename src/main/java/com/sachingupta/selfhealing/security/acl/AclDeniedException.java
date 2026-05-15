package com.sachingupta.selfhealing.security.acl;

public class AclDeniedException extends RuntimeException {
    public AclDeniedException(String message) {
        super(message);
    }
}
