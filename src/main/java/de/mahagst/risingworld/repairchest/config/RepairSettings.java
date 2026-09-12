package de.mahagst.risingworld.repairchest.config;

import java.util.Set;

/** Repair options. Defaults preserve the existing station behavior. */
public record RepairSettings(
		float debounceSeconds,
		// Short delay so storage reflects a take before scanning done -> idle.
		float postTakeScanSeconds,
		float repairSeconds,
		Set<String> allowedChestTypes) {

	public RepairSettings {
		allowedChestTypes = Set.copyOf(allowedChestTypes);
	}

	public static RepairSettings defaults() {
		return new RepairSettings(
				2f,
				0.25f,
				2f,
				Set.of("skullchest", "goldchest", "silverchest", "armoredchest"));
	}
}
