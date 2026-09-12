package de.mahagst.risingworld.repairchest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import net.risingworld.api.definitions.Crafting;
import net.risingworld.api.definitions.Definitions;
import net.risingworld.api.definitions.Items;
import net.risingworld.api.objects.Item;

/**
 * Crafting recipe (API or settings fallback) -> material quote, gold fee, chest allocation.
 */
public final class RepairPricing {
	public record Need(short typeId, int amount, String label, boolean consume, Items.Group group) {
		Need withAmount(int newAmount) {
			return new Need(typeId, newAmount, label, consume, group);
		}

		boolean matches(Item item) {
			if (item == null || item instanceof Item.ObjectItem
					|| item instanceof Item.ConstructionItem || item instanceof Item.ClothingItem) {
				return false;
			}
			if (group == null) {
				return item.getTypeID() == typeId;
			}
			Items.ItemDefinition def = item.getDefinition();
			return def != null && def.group != null && (def.group.value & group.value) == group.value;
		}
	}

	record Removal(int slot, int amount, short typeId) {
	}

	record Plan(List<Need> missing, List<Removal> removals) {
	}

	private record IngredientKey(short typeId, String label, boolean consume, Items.Group group) {
	}

	private RepairPricing() {
	}

	/** Empty Optional = no usable recipe (caller shows NO_RECIPE). */
	static Optional<List<Need>> recipeFor(Item target, RepairSettings settings) {
		return recipeFor(target, settings, Definitions::getRecipe, Definitions::getItemDefinition);
	}

	static Optional<List<Need>> recipeFor(Item target, RepairSettings settings,
			BiFunction<String, Integer, Crafting.Recipe> recipes,
			Function<String, Items.ItemDefinition> itemDefinitions) {
		Items.ItemDefinition def = target.getDefinition();
		if (def == null || def.name == null || def.durability <= 0) {
			return Optional.empty();
		}
		Crafting.Recipe api = recipes.apply(def.name, target.getVariant());
		if (api == null) {
			api = recipes.apply(def.name, 0);
		}
		LinkedHashMap<IngredientKey, Long> counts;
		int craftAmount;
		if (api != null && api.ingredients != null && api.ingredients.length > 0 && api.amount > 0) {
			counts = collectFromApi(api, def.name, target.getDurability(), def.durability, settings);
			craftAmount = api.amount;
		} else {
			RepairSettings.ManualRecipe manual = findManual(def.name, settings);
			if (manual == null) {
				return Optional.empty();
			}
			counts = collectFromManual(manual, def.name, target.getDurability(), def.durability, settings,
					itemDefinitions);
			craftAmount = manual.craftAmount();
		}
		if (counts == null) {
			return Optional.empty();
		}
		if (counts.isEmpty()) {
			return Optional.of(List.of());
		}
		var quote = scale(counts, target.getDurability(), def.durability, craftAmount, settings);
		if (quote.isEmpty() || quote.get().isEmpty()) {
			return quote;
		}
		Items.ItemDefinition gold = itemDefinitions.apply(settings.goldItemName());
		if (gold == null || gold.id <= 0) {
			return Optional.empty();
		}
		return Optional.of(withGoldFee(quote.get(), gold, settings.goldFee(), settings.goldItemName()));
	}

	/** Scale a Crafting.Recipe by damage. Package-visible for unit tests. */
	static Optional<List<Need>> quoteRecipe(Crafting.Recipe recipe, int durability, int maxDurability,
			String targetName, RepairSettings settings) {
		if (recipe == null || recipe.ingredients == null || recipe.ingredients.length == 0 || recipe.amount <= 0) {
			return Optional.empty();
		}
		var counts = collectFromApi(recipe, targetName, durability, maxDurability, settings);
		if (counts == null) {
			return Optional.empty();
		}
		if (counts.isEmpty()) {
			return Optional.of(List.of());
		}
		return scale(counts, durability, maxDurability, recipe.amount, settings);
	}

	/**
	 * Allocate chest slots for a quote. Never consumes the repair target, and never consumes
	 * items with durability (tools/weapons) -- even if a recipe group would match them.
	 */
	static Plan plan(Item[] items, Item target, List<Need> recipe) {
		if (items == null) {
			return new Plan(List.copyOf(recipe), List.of());
		}
		int targetSlot = indexOf(items, target);
		int[] available = new int[items.length];
		int[] consumed = new int[items.length];
		for (int slot = 0; slot < items.length; slot++) {
			Item item = items[slot];
			if (item == null || slot == targetSlot) {
				continue;
			}
			available[slot] = Math.max(item.getStack(), 0);
		}
		var ordered = new ArrayList<>(recipe);
		ordered.sort(Comparator.comparing((Need need) -> need.group() != null).thenComparing(Need::consume));
		var missing = new ArrayList<Need>();
		for (Need need : ordered) {
			int left = need.amount();
			for (int slot = 0; slot < items.length && left > 0; slot++) {
				if (available[slot] == 0 || slot == targetSlot || !need.matches(items[slot])) {
					continue;
				}
				// Consumable mats only -- durable tools may satisfy consume=false catalysts.
				if (need.consume() && hasDurability(items[slot])) {
					continue;
				}
				int used = Math.min(left, available[slot]);
				available[slot] -= used;
				left -= used;
				if (need.consume()) {
					consumed[slot] += used;
				}
			}
			if (left > 0) {
				missing.add(need.withAmount(left));
			}
		}
		var removals = new ArrayList<Removal>();
		if (missing.isEmpty()) {
			for (int slot = 0; slot < consumed.length; slot++) {
				if (consumed[slot] > 0) {
					removals.add(new Removal(slot, consumed[slot], items[slot].getTypeID()));
				}
			}
		}
		return new Plan(List.copyOf(missing), List.copyOf(removals));
	}

	static boolean hasDurability(Item item) {
		if (item == null) {
			return false;
		}
		Items.ItemDefinition def = item.getDefinition();
		return def != null && def.durability > 0;
	}

	private static int indexOf(Item[] items, Item target) {
		if (items == null || target == null) {
			return -1;
		}
		for (int slot = 0; slot < items.length; slot++) {
			Item item = items[slot];
			if (item == target || (item != null && item.equals(target))) {
				return slot;
			}
		}
		return -1;
	}

	/** null = unusable; empty = already full. */
	private static LinkedHashMap<IngredientKey, Long> collectFromApi(Crafting.Recipe recipe, String targetName,
			int durability, int maxDurability, RepairSettings settings) {
		if (maxDurability <= 0) {
			return null;
		}
		int current = clamped(durability, maxDurability);
		if (current == maxDurability) {
			return new LinkedHashMap<>();
		}
		boolean fullPriceBand = isFullPriceBand(current, maxDurability, settings);
		var counts = new LinkedHashMap<IngredientKey, Long>();
		boolean sawValid = false;
		for (Crafting.Recipe.Ingredient ingredient : recipe.ingredients) {
			if (ingredient == null || ingredient.count <= 0) {
				continue;
			}
			Items.ItemDefinition def = ingredient.itemDef;
			Items.Group group = ingredient.group;
			String name;
			if (def != null) {
				if (def.name == null || def.id <= 0) {
					continue;
				}
				name = def.name;
				group = null;
			} else if (group != null && group != Items.Group.None) {
				name = "any " + group.name().toLowerCase(Locale.ROOT);
			} else {
				continue;
			}
			sawValid = true;
			if (!fullPriceBand && isFullPriceOnly(name, targetName, settings)) {
				continue;
			}
			counts.merge(new IngredientKey(def == null ? (short) 0 : def.id, name, ingredient.consume, group),
					(long) ingredient.count, Long::sum);
		}
		return sawValid && !counts.isEmpty() ? counts : null;
	}

	/** null = unusable; empty = already full. */
	private static LinkedHashMap<IngredientKey, Long> collectFromManual(RepairSettings.ManualRecipe manual,
			String targetName, int durability, int maxDurability, RepairSettings settings,
			Function<String, Items.ItemDefinition> itemDefinitions) {
		if (manual.ingredients().isEmpty() || manual.craftAmount() <= 0 || maxDurability <= 0) {
			return null;
		}
		int current = clamped(durability, maxDurability);
		if (current == maxDurability) {
			return new LinkedHashMap<>();
		}
		boolean fullPriceBand = isFullPriceBand(current, maxDurability, settings);
		var counts = new LinkedHashMap<IngredientKey, Long>();
		boolean sawValid = false;
		for (var ingredient : manual.ingredients()) {
			if (ingredient == null || ingredient.count() <= 0 || ingredient.itemName() == null) {
				continue;
			}
			Items.ItemDefinition def = itemDefinitions.apply(ingredient.itemName());
			if (def == null || def.name == null || def.id <= 0) {
				return null;
			}
			sawValid = true;
			if (!fullPriceBand && isFullPriceOnly(def.name, targetName, settings)) {
				continue;
			}
			counts.merge(new IngredientKey(def.id, def.name, ingredient.consume(), null),
					(long) ingredient.count(), Long::sum);
		}
		return sawValid && !counts.isEmpty() ? counts : null;
	}

	private static Optional<List<Need>> scale(LinkedHashMap<IngredientKey, Long> counts, int durability,
			int maxDurability, int craftAmount, RepairSettings settings) {
		int current = clamped(durability, maxDurability);
		boolean fullPriceBand = isFullPriceBand(current, maxDurability, settings);
		long charged = fullPriceBand ? maxDurability : maxDurability - current;
		long denominator = (long) maxDurability * craftAmount;
		var needs = new ArrayList<Need>();
		for (var entry : counts.entrySet()) {
			IngredientKey key = entry.getKey();
			int amount = key.consume()
					? (int) Math.ceilDiv(entry.getValue() * charged, denominator)
					: entry.getValue().intValue();
			needs.add(new Need(key.typeId(), amount, key.label(), key.consume(), key.group()));
		}
		return Optional.of(List.copyOf(needs));
	}

	private static RepairSettings.ManualRecipe findManual(String targetName, RepairSettings settings) {
		for (var recipe : settings.manualRecipes()) {
			if (recipe.targetName().equals(targetName)) {
				return recipe;
			}
		}
		return null;
	}

	private static int clamped(int durability, int maxDurability) {
		return Math.max(0, Math.min(durability, maxDurability));
	}

	private static boolean isFullPriceBand(int current, int maxDurability, RepairSettings settings) {
		return (long) current * 100 <= (long) maxDurability * settings.fullPriceRemainingPercent();
	}

	private static List<Need> withGoldFee(List<Need> quote, Items.ItemDefinition gold, int fee, String goldName) {
		var needs = new ArrayList<>(quote);
		for (int i = 0; i < needs.size(); i++) {
			Need need = needs.get(i);
			if (need.group() == null && need.typeId() == gold.id && need.consume()) {
				needs.set(i, need.withAmount(need.amount() + fee));
				return List.copyOf(needs);
			}
		}
		needs.add(new Need(gold.id, fee, goldName, true, null));
		return List.copyOf(needs);
	}

	private static boolean isFullPriceOnly(String ingredientName, String targetName, RepairSettings settings) {
		if (targetName == null) {
			return false;
		}
		for (var rule : settings.fullPriceOnlyIngredients()) {
			if (rule.ingredientName().equals(ingredientName) && rule.forTargets().contains(targetName)) {
				return true;
			}
		}
		return false;
	}
}
