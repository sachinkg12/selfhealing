package com.sachingupta.selfhealing.scenarios;

/**
 * One executable demonstration. Each scenario exercises a specific claim of the system. Scenarios
 * are first-class beans so adding a new one is purely additive (Open/Closed).
 */
public interface Scenario {

    /** Short identifier used on the CLI (e.g. "happy-path"). */
    String slug();

    /** Human-readable title for the run banner. */
    String title();

    /** Short summary of the claim this scenario exercises. */
    String claim();

    /** Run the scenario; print its output and verdict to the console. */
    void run();
}
