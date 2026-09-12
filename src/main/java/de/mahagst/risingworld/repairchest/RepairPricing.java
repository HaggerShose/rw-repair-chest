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

/** Live crafting recipe -> scaled material needs, plus flat gold fee and chest allocation. */
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

	record Removal(int slot, int amount) {
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
		Crafting.Recipe recipe = recipes.apply(def.name, target.getVariant());
		if (recipe == null) {
			recipe = recipes.apply(def.name, 0);
		}
		if (recipe == null || recipe.ingredients == null || recipe.ingredients.length == 0 || recipe.amount <= 0) {
			return Optional.empty();
		}
		var quote = quoteRecipe(recipe, target.getDurability(), def.durability, settings.fullPriceRemainingPercent());
		if (quote.isEmpty() || quote.get().isEmpty()) {
			return quote;
		}
		Items.ItemDefinition gold = itemDefinitions.apply(settings.goldItemName());
		if (gold == null || gold.id <= 0) {
			return Optional.empty();
		}
		int goldFee = settings.goldFee();
		var needs = new ArrayList<>(quote.get());
		for (int i = 0; i < needs.size(); i++) {
			Need need = needs.get(i);
			if (need.group() == null && need.typeId() == gold.id && need.consume()) {
				needs.set(i, need.withAmount(need.amount() + goldFee));
				return Optional.of(List.copyOf(needs));
			}
		}
		needs.add(new Need(gold.id, goldFee, settings.goldItemName(), true, null));
		return Optional.of(List.copyOf(needs));
	}

	static Optional<List<Need>> quoteRecipe(Crafting.Recipe recipe, int durability, int maxDurability,
			int fullPriceRemainingPercent) {
		if (maxDurability <= 0 || recipe == null || recipe.amount <= 0
				|| recipe.ingredients == null || recipe.ingredients.length == 0) {
			return Optional.empty();
		}
		int current = Math.max(0, Math.min(durability, maxDurability));
		if (current == maxDurability) {
			return Optional.of(List.of());
		}
		long charged = (long) current * 100 <= (long) maxDurability * fullPriceRemainingPercent
				? maxDurability
				: maxDurability - current;
		var counts = new LinkedHashMap<IngredientKey, Long>();
		for (Crafting.Recipe.Ingredient ingredient : recipe.ingredients) {
			if (ingredient == null || ingredient.count <= 0) {
				continue;
			}
			Items.ItemDefinition def = ingredient.itemDef;
			Items.Group group = ingredient.group;
			if (def != null) {
				if (def.name == null || def.id <= 0) {
					continue;
				}
				group = null;
			} else if (group == null || group == Items.Group.None) {
				continue;
			}
			var key = new IngredientKey(def == null ? (short) 0 : def.id,
					def == null ? "any " + group.name().toLowerCase(Locale.ROOT) : def.name,
					ingredient.consume, group);
			counts.merge(key, (long) ingredient.count, Long::sum);
		}
		if (counts.isEmpty()) {
			return Optional.empty();
		}
		var needs = new ArrayList<Need>();
		for (var entry : counts.entrySet()) {
			IngredientKey key = entry.getKey();
			long numerator = entry.getValue() * charged;
			long denominator = (long) maxDurability * recipe.amount;
			int amount = key.consume()
					? (int) (numerator / denominator + (numerator % denominator == 0 ? 0 : 1))
					: entry.getValue().intValue();
			needs.add(new Need(key.typeId(), amount, key.label(), key.consume(), key.group()));
		}
		return Optional.of(List.copyOf(needs));
	}

	/** Allocate recipe ingredients to chest slots without double-counting stacks. */
	static Plan plan(Item[] items, Item target, List<Need> recipe) {
		if (items == null) {
			return new Plan(List.copyOf(recipe), List.of());
		}
		int[] available = new int[items.length];
		int[] consumed = new int[items.length];
		for (int slot = 0; slot < items.length; slot++) {
			Item item = items[slot];
			if (item != null && !item.equals(target)) {
				available[slot] = Math.max(item.getStack(), 0);
			}
		}
		var ordered = new ArrayList<>(recipe);
		ordered.sort(Comparator.comparing((Need need) -> need.group() != null).thenComparing(Need::consume));
		var missing = new ArrayList<Need>();
		for (Need need : ordered) {
			int left = need.amount();
			for (int slot = 0; slot < items.length && left > 0; slot++) {
				if (available[slot] == 0 || !need.matches(items[slot])) {
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
					removals.add(new Removal(slot, consumed[slot]));
				}
			}
		}
		return new Plan(List.copyOf(missing), List.copyOf(removals));
	}
}
