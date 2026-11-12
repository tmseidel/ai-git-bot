package org.remus.giteabot.agent.packagemanager;

/**
 * The package manager a checkout declares.
 *
 * <p>Only the two managers the coding agent has tooling for are named; every
 * other project — no marker at all, or a lockfile for a manager the agent does
 * not drive — stays {@link #UNKNOWN} so the existing npm tooling keeps
 * behaving exactly as before.</p>
 */
public enum PackageManager {
    NPM,
    YARN,
    UNKNOWN
}
