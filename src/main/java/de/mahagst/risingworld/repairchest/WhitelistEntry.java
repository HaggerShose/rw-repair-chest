package de.mahagst.risingworld.repairchest;

/** Whitelist row. variant null = any variant. */
public record WhitelistEntry(String itemKind, short typeId, Integer variant, String label) {

	public boolean matches(String kind, short itemTypeId, int itemVariant) {
		if (!itemKind.equals(kind) || typeId != itemTypeId) {
			return false;
		}
		return variant == null || variant == itemVariant;
	}
}
