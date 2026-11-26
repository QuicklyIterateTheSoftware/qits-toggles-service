package eu.wohlben.qits.toggles.store;

/**
 * What the startup reconcile is told about one declared toggle: its slug, its description and its
 * default value. The service's DSL turns each declaration into one of these.
 */
public record Declaration(String slug, String description, boolean defaultValue) {}
