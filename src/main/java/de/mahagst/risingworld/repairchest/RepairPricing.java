package de.mahagst.risingworld.repairchest;

import java.util.ArrayList;
import java.util.List;

import net.risingworld.api.definitions.Definitions;
import net.risingworld.api.definitions.Items;
import net.risingworld.api.objects.Item;

/**
 * Repair material quote from missing durability percent.
 * Tune per-item recipes below. Gold staffel is always added for every repair.
 */
final class RepairPricing {
	static final int GOLD_BASE = 10;
	static final int GOLD_UNDER_15 = 15;
	static final int GOLD_UNDER_10 = 20;
	static final int GOLD_MID_PCT = 15;
	static final int GOLD_HIGH_PCT = 10;
	static final int VARIANT = 0;

	static final short FALLBACK_GOLD_INGOT = 451;
	static final short FALLBACK_BOARD = 145;

	/** One consumed material: amount = ceil(missingPct / divisor). Divisor 0 skips. */
	record Mat(String name, short fallback, double divisor) {
	}

	/**
	 * Per-item recipe. Tune mats / knife / board here -- one place for all prices.
	 * boardBelowPct 0 = no circuit board. knifeCatalyst = 1x any knife, not consumed.
	 */
	record ItemRecipe(String name, short typeId, Mat[] mats, boolean knifeCatalyst, int boardBelowPct) {
	}

	static final ItemRecipe MINING_DRILL = item(
			"miningdrill", 134,
			false, 15,
			mat("coal", 430, 2.5),
			mat("ironingot", 450, 2.5),
			mat("tungsteningot", 453, 5));
	static final ItemRecipe CHAINSAW = item(
			"chainsaw", 132,
			false, 15,
			mat("coal", 430, 2.5),
			mat("ironplate", 470, 2.5),
			mat("tungstenplate", 473, 5));
	static final ItemRecipe TRIMMER = item(
			"trimmer", 136,
			false, 15,
			mat("coal", 430, 2.5),
			mat("ironplate", 470, 5),
			mat("tungstenwire", 493, 2.5));
	static final ItemRecipe BOW1 = item(
			"bow1", 195,
			true, 0,
			mat("yarn", 162, 1),
			mat("lumber", 502, 2.5));
	static final ItemRecipe CROSSBOW = item(
			"crossbow", 205,
			true, 0,
			mat("yarn", 162, 1),
			mat("ironplate", 470, 2.5));

	private static final ItemRecipe[] RECIPES = {
			MINING_DRILL, CHAINSAW, TRIMMER, BOW1, CROSSBOW
	};

	record Need(short typeId, int variant, int amount, String label, boolean consume) {
	}

	private RepairPricing() {
	}

	static List<Need> recipeFor(Item target) {
		int remaining = remainingPct(target);
		int missing = 100 - remaining;
		if (missing < 1) {
			return List.of();
		}
		Items.ItemDefinition def = target.getDefinition();
		String name = def == null || def.name == null ? "" : def.name;
		ItemRecipe recipe = lookup(name, target.getTypeID());
		var needs = new ArrayList<Need>();
		if (recipe != null) {
			for (Mat mat : recipe.mats()) {
				add(needs, mat.name(), mat.fallback(), amountFor(missing, mat.divisor()));
			}
			if (recipe.knifeCatalyst()) {
				needs.add(knifeNeed());
			}
		}
		add(needs, "goldingot", FALLBACK_GOLD_INGOT, goldAmount(remaining));
		if (recipe != null && recipe.boardBelowPct() > 0 && remaining < recipe.boardBelowPct()) {
			add(needs, "circuitboard", FALLBACK_BOARD, 1);
		}
		return needs;
	}

	/** floor(current * 100 / max). Full item -> 100. */
	static int remainingPct(Item item) {
		Items.ItemDefinition def = item.getDefinition();
		if (def == null || def.durability <= 0) {
			return 100;
		}
		int max = def.durability;
		int cur = item.getDurability();
		if (cur < 0) {
			cur = 0;
		}
		if (cur > max) {
			cur = max;
		}
		return (cur * 100) / max;
	}

	static boolean isKnife(Item item) {
		if (item == null) {
			return false;
		}
		Items.ItemDefinition def = item.getDefinition();
		return def != null && def.type == Items.Type.Knife;
	}

	private static Need knifeNeed() {
		return new Need((short) 0, VARIANT, 1, "knife", false);
	}

	private static int goldAmount(int remaining) {
		if (remaining < GOLD_HIGH_PCT) {
			return GOLD_UNDER_10;
		}
		if (remaining < GOLD_MID_PCT) {
			return GOLD_UNDER_15;
		}
		return GOLD_BASE;
	}

	/** ceil(missingPct / divisor); amount is always a whole stack count. */
	private static int amountFor(int missing, double divisor) {
		if (divisor <= 0) {
			return 0;
		}
		return (int) Math.ceil(missing / divisor);
	}

	private static ItemRecipe lookup(String name, short typeId) {
		for (ItemRecipe recipe : RECIPES) {
			if (recipe.name().equals(name) || recipe.typeId() == typeId) {
				return recipe;
			}
		}
		return null;
	}

	private static ItemRecipe item(
			String name,
			int typeId,
			boolean knifeCatalyst,
			int boardBelowPct,
			Mat... mats) {
		return new ItemRecipe(name, (short) typeId, mats, knifeCatalyst, boardBelowPct);
	}

	private static Mat mat(String name, int fallback, double divisor) {
		return new Mat(name, (short) fallback, divisor);
	}

	private static void add(List<Need> needs, String name, short fallback, int amount) {
		if (amount <= 0) {
			return;
		}
		needs.add(new Need(resolveItemId(name, fallback), VARIANT, amount, name, true));
	}

	private static short resolveItemId(String name, short fallback) {
		Items.ItemDefinition def = Definitions.getItemDefinition(name);
		if (def != null) {
			return def.id;
		}
		return fallback;
	}
}
