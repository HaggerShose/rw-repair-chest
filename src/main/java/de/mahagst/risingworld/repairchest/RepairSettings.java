package de.mahagst.risingworld.repairchest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Operator knobs. {@code settings.json} is the live source; {@link #defaults()} seeds a missing file
 * and unit tests. Startup/reload replaces the SQLite whitelist from {@link #whitelistSeeds()}.
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
		/**
		 * Recipe ingredients only charged in the full-price band (&lt;= fullPriceRemainingPercent remaining)
		 * and only for the listed repair targets.
		 */
		List<FullPriceOnlyIngredient> fullPriceOnlyIngredients,
		/**
		 * Fallback recipes when {@code Definitions.getRecipe} has none (e.g. morningstar1).
		 * API recipes always win when present.
		 */
		List<ManualRecipe> manualRecipes,
		Set<String> allowedChestTypes,
		List<WhitelistSeed> whitelistSeeds,
		Set<String> allowedUids) {

	public record WhitelistSeed(String name, short fallbackTypeId) {
	}

	/** e.g. circuitboard only for miningdrill/chainsaw/trimmer when nearly broken. */
	public record FullPriceOnlyIngredient(String ingredientName, Set<String> forTargets) {
		public FullPriceOnlyIngredient {
			forTargets = Set.copyOf(forTargets);
		}
	}

	/** Settings-defined repair recipe for a target item name. */
	public record ManualRecipe(String targetName, int craftAmount, List<ManualIngredient> ingredients) {
		public ManualRecipe {
			ingredients = List.copyOf(ingredients);
		}

		public ManualRecipe(String targetName, List<ManualIngredient> ingredients) {
			this(targetName, 1, ingredients);
		}
	}

	/** One ingredient line; {@code itemName} is resolved via {@code Definitions.getItemDefinition}. */
	public record ManualIngredient(String itemName, int count, boolean consume) {
		public ManualIngredient(String itemName, int count) {
			this(itemName, count, true);
		}
	}

	public RepairSettings {
		fullPriceOnlyIngredients = List.copyOf(fullPriceOnlyIngredients);
		manualRecipes = List.copyOf(manualRecipes);
		allowedChestTypes = Set.copyOf(allowedChestTypes);
		whitelistSeeds = List.copyOf(whitelistSeeds);
		allowedUids = Set.copyOf(allowedUids);
	}

	public RepairSettings withWhitelist(List<WhitelistSeed> seeds) {
		return new RepairSettings(
				debounceSeconds,
				postTakeScanSeconds,
				repairSeconds,
				interactDistance,
				fullPriceRemainingPercent,
				goldFee,
				goldItemName,
				fullPriceOnlyIngredients,
				manualRecipes,
				allowedChestTypes,
				seeds,
				allowedUids);
	}

	public RepairSettings addingRepairable(String name, short fallbackTypeId) {
		if (name == null || name.isBlank()) {
			return this;
		}
		String trimmed = name.trim();
		for (WhitelistSeed seed : whitelistSeeds) {
			if (seed.name().equals(trimmed)) {
				return this;
			}
		}
		var next = new ArrayList<>(whitelistSeeds);
		next.add(new WhitelistSeed(trimmed, fallbackTypeId));
		return withWhitelist(next);
	}

	public RepairSettings removingRepairable(String name) {
		if (name == null || name.isBlank()) {
			return this;
		}
		String trimmed = name.trim();
		var next = new ArrayList<WhitelistSeed>();
		for (WhitelistSeed seed : whitelistSeeds) {
			if (!seed.name().equals(trimmed)) {
				next.add(seed);
			}
		}
		if (next.size() == whitelistSeeds.size()) {
			return this;
		}
		return withWhitelist(next);
	}

	public static RepairSettings defaults() {
		return new RepairSettings(
				0.5f,
				0.25f,
				2f,
				5f,
				15,
				5,
				"goldingot",
				List.of(new FullPriceOnlyIngredient(
						"circuitboard",
						Set.of("miningdrill", "chainsaw", "trimmer"))),
				List.of(new ManualRecipe("morningstar1", List.of(
						new ManualIngredient("ironplate", 12),
						new ManualIngredient("tungstenplate", 6)))),
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
