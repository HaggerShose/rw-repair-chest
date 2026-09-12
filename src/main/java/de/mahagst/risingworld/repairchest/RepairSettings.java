package de.mahagst.risingworld.repairchest;

import java.util.List;
import java.util.Set;

/**
 * Operator knobs. Edit {@link #defaults()} to change timers, chests, whitelist seeds, fees, UIDs.
 * Startup replaces the SQLite whitelist from {@link #whitelistSeeds()}.
 */
public record RepairSettings(
		float debounceSeconds,
		float postTakeScanSeconds,
		float repairSeconds,
		/** LOS focus and identity distance (blocks). */
		float interactDistance,
		int fullPriceRemainingPercent,
		int goldFee,
		String goldItemName,
		Set<String> allowedChestTypes,
		List<WhitelistSeed> whitelistSeeds,
		Set<String> allowedUids) {

	public record WhitelistSeed(String name, short fallbackTypeId) {
	}

	public RepairSettings {
		allowedChestTypes = Set.copyOf(allowedChestTypes);
		whitelistSeeds = List.copyOf(whitelistSeeds);
		allowedUids = Set.copyOf(allowedUids);
	}

	public static RepairSettings defaults() {
		return new RepairSettings(
				2f,
				0.25f,
				2f,
				5f,
				15,
				5,
				"goldingot",
				Set.of("skullchest", "goldchest", "silverchest", "armoredchest"),
				List.of(
						new WhitelistSeed("miningdrill", (short) 134),
						new WhitelistSeed("chainsaw", (short) 132),
						new WhitelistSeed("trimmer", (short) 136),
						new WhitelistSeed("bow1", (short) 195),
						new WhitelistSeed("crossbow", (short) 205),
						new WhitelistSeed("repeater", (short) 300),
						new WhitelistSeed("morningstar1", (short) 250)),
				Set.of("76561198002368372"));
	}
}
