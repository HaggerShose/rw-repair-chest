package de.mahagst.risingworld.repairchest;

import java.util.ArrayList;
import java.util.List;

import net.risingworld.api.definitions.Definitions;
import net.risingworld.api.definitions.Items;
import net.risingworld.api.objects.Item;

/**
 * Repair material quote from missing durability percent.
 * Gold ingot staffel is shared. Other mats are per tool -- divisor 0 skips.
 */
final class RepairPricing {
	static final int GOLD_BASE = 10;
	static final int GOLD_UNDER_15 = 15;
	static final int GOLD_UNDER_10 = 20;
	static final int GOLD_MID_PCT = 15;
	static final int GOLD_HIGH_PCT = 10;
	static final int VARIANT = 0;

	static final short FALLBACK_COAL = 430;
	static final short FALLBACK_IRON_INGOT = 450;
	static final short FALLBACK_IRON_PLATE = 470;
	static final short FALLBACK_TUNGSTEN_INGOT = 453;
	static final short FALLBACK_TUNGSTEN_PLATE = 473;
	static final short FALLBACK_TUNGSTEN_WIRE = 493;
	static final short FALLBACK_ALUMINIUM_INGOT = 452;
	static final short FALLBACK_ALUMINIUM_PLATE = 472;
	static final short FALLBACK_ALUMINIUM_WIRE = 492;
	static final short FALLBACK_GOLD_INGOT = 451;
	static final short FALLBACK_GOLD_PLATE = 471;
	static final short FALLBACK_BOARD = 145;

	/**
	 * Per-tool percent divisors (double, e.g. 2.5 / 1.25). RW names use plate.
	 * Divisor 0 skips that material. boardBelowPct 0 = no circuit board.
	 */
	record ToolRecipe(
			double coal,
			double ironIngot,
			double ironPlate,
			double tungstenIngot,
			double tungstenPlate,
			double tungstenWire,
			double aluminiumIngot,
			double aluminiumPlate,
			double aluminiumWire,
			double goldPlate,
			int boardBelowPct) {
	}

	static final ToolRecipe MINING_DRILL = new ToolRecipe(
			2.5, 2.5, 0,
			5, 0, 0,
			0, 0, 0,
			0,
			15);
	static final ToolRecipe CHAINSAW = new ToolRecipe(
			2.5, 0, 2.5,
			0, 2.5, 0,
			0, 0, 0,
			0,
			15);
	static final ToolRecipe TRIMMER = new ToolRecipe(
			2.5, 0, 5,
			0, 0, 2.5,
			0, 0, 0,
			0,
			15);

	record Need(short typeId, int variant, int amount, String label) {
	}

	private RepairPricing() {
	}

	static List<Need> recipeFor(Item target) {
		int remaining = remainingPct(target);
		int missing = 100 - remaining;
		if (missing < 1) {
			return List.of();
		}
		var needs = new ArrayList<Need>();
		ToolRecipe spec = toolRecipe(target);
		if (spec != null) {
			add(needs, "coal", FALLBACK_COAL, amountFor(missing, spec.coal()));
			add(needs, "ironingot", FALLBACK_IRON_INGOT, amountFor(missing, spec.ironIngot()));
			add(needs, "ironplate", FALLBACK_IRON_PLATE, amountFor(missing, spec.ironPlate()));
			add(needs, "tungsteningot", FALLBACK_TUNGSTEN_INGOT, amountFor(missing, spec.tungstenIngot()));
			add(needs, "tungstenplate", FALLBACK_TUNGSTEN_PLATE, amountFor(missing, spec.tungstenPlate()));
			add(needs, "tungstenwire", FALLBACK_TUNGSTEN_WIRE, amountFor(missing, spec.tungstenWire()));
			add(needs, "aluminiumingot", FALLBACK_ALUMINIUM_INGOT, amountFor(missing, spec.aluminiumIngot()));
			add(needs, "aluminiumplate", FALLBACK_ALUMINIUM_PLATE, amountFor(missing, spec.aluminiumPlate()));
			add(needs, "aluminiumwire", FALLBACK_ALUMINIUM_WIRE, amountFor(missing, spec.aluminiumWire()));
			add(needs, "goldplate", FALLBACK_GOLD_PLATE, amountFor(missing, spec.goldPlate()));
		}
		add(needs, "goldingot", FALLBACK_GOLD_INGOT, goldAmount(remaining));
		if (spec != null && spec.boardBelowPct() > 0 && remaining < spec.boardBelowPct()) {
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

	private static ToolRecipe toolRecipe(Item target) {
		Items.ItemDefinition def = target.getDefinition();
		String name = def == null ? "" : def.name;
		if (name == null) {
			name = "";
		}
		return switch (name) {
			case "miningdrill" -> MINING_DRILL;
			case "chainsaw" -> CHAINSAW;
			case "trimmer" -> TRIMMER;
			default -> switch (target.getTypeID()) {
				case 134 -> MINING_DRILL;
				case 132 -> CHAINSAW;
				case 136 -> TRIMMER;
				default -> null;
			};
		};
	}

	private static void add(List<Need> needs, String name, short fallback, int amount) {
		if (amount <= 0) {
			return;
		}
		needs.add(new Need(resolveItemId(name, fallback), VARIANT, amount, name));
	}

	private static short resolveItemId(String name, short fallback) {
		Items.ItemDefinition def = Definitions.getItemDefinition(name);
		if (def != null) {
			return def.id;
		}
		return fallback;
	}
}
