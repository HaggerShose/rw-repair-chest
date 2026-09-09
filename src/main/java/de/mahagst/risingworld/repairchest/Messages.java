package de.mahagst.risingworld.repairchest;

import java.util.List;

/**
 * Player chat and station sign copy. Tune here without touching logic.
 */
final class Messages {
	static final String READY = "Repair station ready.";
	static final String ONE_AT_A_TIME = "Just one item at a time.";
	static final String ALREADY_FULL = "Already at 100%.";
	static final String REPAIRING = "Repairing...";
	static final String COMPLETE = "<color=#00ff00>Repair complete.</color>\nTake your item.";
	static final String PUT_DAMAGED_ITEM = "Put your damaged Item into the Chest";

	static final String USAGE_MAKE_CHEST = "Usage: /make-repair-chest <NAME>";
	static final String USAGE_MAKE_SIGN = "Usage: /make-repair-sign <NAME>";
	static final String NOT_STORAGE_CHEST = "That is not a storage chest.";
	static final String CHEST_ALREADY_REGISTERED = "Chest is already registered.";
	static final String NAME_IN_USE = "Name already in use.";
	static final String SAVE_FAILED = "Could not save repair chest.";
	static final String NO_CHEST_WITH_NAME = "No repair chest with that name.";
	static final String CHEST_INVALID = "Chest is no longer valid, entry removed.";
	static final String STATION_HAS_SIGN = "Station already has a sign.";
	static final String SIGN_ALREADY_LINKED = "Sign is already linked to a station.";
	static final String CHEST_NOT_REGISTERED = "Chest is not registered.";
	static final String CHEST_REMOVED = "Repair chest removed.";
	static final String NO_CHEST_IN_FOCUS = "No chest in focus.";
	static final String NOT_A_STORAGE = "That is not a storage.";
	static final String TRANSIENT_UNSUPPORTED = "Transient storage is not supported.";
	static final String NO_SIGN_IN_FOCUS = "No sign in focus.";
	static final String NOT_A_SIGN = "That is not a sign.";

	private Messages() {
	}

	static String needs(List<RepairPricing.Need> missing) {
		StringBuilder text = new StringBuilder("Need:");
		for (RepairPricing.Need need : missing) {
			text.append('\n').append(need.amount()).append("x ").append(need.label());
			if (!need.consume()) {
				text.append(" (tool)");
			}
		}
		return text.toString();
	}

	static String chestTypeNotAllowed(String typeName, short typeId) {
		return "Chest type not allowed: " + typeName + " (id " + typeId + ")";
	}

	static String chestCreated(String name) {
		return "Repair chest created: " + name;
	}

	static String signLinked(String name) {
		return "Sign linked to " + name;
	}

	static String info(
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
