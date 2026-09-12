package de.mahagst.risingworld.repairchest;

import java.util.List;

/**
 * Player chat and station sign copy. Tune here without touching logic.
 */
public final class Messages {
	public static final String READY = "Repair station ready.";
	public static final String ONE_AT_A_TIME = "Just one item at a time.";
	public static final String ALREADY_FULL = "Already at 100%.";
	public static final String NO_RECIPE = "No usable crafting recipe.\nCannot repair this item.";
	public static final String REPAIRING = "Repairing...";
	public static final String COMPLETE = "<color=#00ff00>Repair complete.</color>\nTake your item.";
	public static final String PUT_DAMAGED_ITEM = "Put your damaged Item into the Chest";

	public static final String USAGE_MAKE_CHEST = "Usage: /make-repair-chest <NAME>";
	public static final String USAGE_MAKE_SIGN = "Usage: /make-repair-sign <NAME>";
	public static final String NOT_STORAGE_CHEST = "That is not a storage chest.";
	public static final String CHEST_ALREADY_REGISTERED = "Chest is already registered.";
	public static final String NAME_IN_USE = "Name already in use.";
	public static final String SAVE_FAILED = "Could not save repair chest.";
	public static final String NO_CHEST_WITH_NAME = "No repair chest with that name.";
	public static final String CHEST_INVALID = "Chest is no longer valid, entry removed.";
	public static final String STATION_HAS_SIGN = "Station already has a sign.";
	public static final String SIGN_ALREADY_LINKED = "Sign is already linked to a station.";
	public static final String CHEST_NOT_REGISTERED = "Chest is not registered.";
	public static final String CHEST_REMOVED = "Repair chest removed.";
	public static final String NO_CHEST_IN_FOCUS = "No chest in focus.";
	public static final String NOT_A_STORAGE = "That is not a storage.";
	public static final String TRANSIENT_UNSUPPORTED = "Transient storage is not supported.";
	public static final String NO_SIGN_IN_FOCUS = "No sign in focus.";
	public static final String NOT_A_SIGN = "That is not a sign.";
	public static final String SETTINGS_RELOAD_FAILED = "Could not reload settings.json (kept previous settings).";

	private Messages() {
	}

	public static String needs(List<RepairPricing.Need> missing) {
		StringBuilder text = new StringBuilder("Need:");
		for (RepairPricing.Need need : missing) {
			text.append('\n').append(need.amount()).append("x ").append(need.label());
			if (!need.consume()) {
				text.append(" (tool)");
			}
		}
		return text.toString();
	}

	public static String chestTypeNotAllowed(String typeName, short typeId) {
		return "Chest type not allowed: " + typeName + " (id " + typeId + ")";
	}

	public static String chestCreated(String name) {
		return "Repair chest created: " + name;
	}

	public static String signLinked(String name) {
		return "Sign linked to " + name;
	}

	public static String settingsReloaded(int whitelistCount) {
		return "Settings reloaded. Whitelist: " + whitelistCount + " items.";
	}

	public static String info(
			String name,
			String objectType,
			short typeId,
			String state,
			boolean debouncePending,
			boolean hasSign,
			String whitelistSummary) {
		return "Repair Chest: " + name
				+ ", type " + objectType
				+ " (id " + typeId + ")"
				+ ", state " + state
				+ ", debounce " + (debouncePending ? "yes" : "no")
				+ ", sign " + (hasSign ? "yes" : "no")
				+ ", whitelist: " + whitelistSummary;
	}
}
