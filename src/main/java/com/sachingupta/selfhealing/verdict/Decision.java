package com.sachingupta.selfhealing.verdict;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Verdict outcome. Serializes to lowercase JSON on the wire ({@code refuse}, {@code remediate}).
 */
public enum Decision {
    REMEDIATE,
    REFUSE;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
