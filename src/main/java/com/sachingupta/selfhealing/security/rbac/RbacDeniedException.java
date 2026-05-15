package com.sachingupta.selfhealing.security.rbac;

public class RbacDeniedException extends RuntimeException {
    public RbacDeniedException(String message) {
        super(message);
    }
}
