package de.mahagst.risingworld.repairchest.integration;

/** Lifecycle contract for an optional settings UI supplied by another plugin. */
public interface SettingsUiIntegration {
	void register();

	void unregister();
}
