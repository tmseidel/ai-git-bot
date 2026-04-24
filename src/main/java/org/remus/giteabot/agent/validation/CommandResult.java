package org.remus.giteabot.agent.validation;

/**
 * Result of running an external command (e.g. {@code git clone}).
 */
record CommandResult(boolean success, String output) {}

