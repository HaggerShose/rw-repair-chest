package de.mahagst.risingworld.repairchest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Operator knobs. {@code settings.json} is the live source; {@link #defaults()} seeds a missing file
 * and unit tests. Startup/reload replaces the SQLite whitelist from {@link #whitelist()}.
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
		List<String> whitelist) {

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
		whitelist = List.copyOf(whitelist);
	}

	public RepairSettings withWhitelist(List<String> names) {
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
				names);
	}

	public RepairSettings addingRepairable(String name) {
		if (name == null || name.isBlank()) {
			return this;
		}
		String trimmed = name.trim();
		if (whitelist.contains(trimmed)) {
			return this;
		}
		var next = new ArrayList<>(whitelist);
		next.add(trimmed);
		return withWhitelist(next);
	}

	public RepairSettings removingRepairable(String name) {
		if (name == null || name.isBlank()) {
			return this;
		}
		String trimmed = name.trim();
		if (!whitelist.contains(trimmed)) {
			return this;
		}
		var next = new ArrayList<>(whitelist);
		next.remove(trimmed);
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
				List.of("miningdrill", "chainsaw", "trimmer", "bow1", "crossbow", "repeater", "morningstar1"));
	}
}
