package de.mahagst.risingworld.repairchest;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepairSettingsTest {
	@Test
	void withWhitelistReplacesSeedsAndKeepsOtherKnobs() {
		var original = RepairSettings.defaults();
		var seeds = List.of(new RepairSettings.WhitelistSeed("pickaxe", (short) 9));
		var updated = original.withWhitelist(seeds);
		assertEquals(seeds, updated.whitelistSeeds());
		assertEquals(original.goldFee(), updated.goldFee());
		assertEquals(original.goldItemName(), updated.goldItemName());
		assertEquals(original.allowedChestTypes(), updated.allowedChestTypes());
		assertEquals(original.manualRecipes(), updated.manualRecipes());
		assertNotSame(original, updated);
	}

	@Test
	void addingRepairableAppendsUntilNameExists() {
		var original = RepairSettings.defaults();
		var added = original.addingRepairable("pickaxe", (short) 9);
		assertEquals(original.whitelistSeeds().size() + 1, added.whitelistSeeds().size());
		assertEquals(new RepairSettings.WhitelistSeed("pickaxe", (short) 9),
				added.whitelistSeeds().get(added.whitelistSeeds().size() - 1));
		assertSame(added, added.addingRepairable("pickaxe", (short) 99));
		assertSame(original, original.addingRepairable("  ", (short) 1));
		assertSame(original, original.addingRepairable(null, (short) 1));
	}

	@Test
	void removingRepairableDropsByName() {
		var original = RepairSettings.defaults();
		var without = original.removingRepairable("chainsaw");
		assertEquals(original.whitelistSeeds().size() - 1, without.whitelistSeeds().size());
		assertTrue(without.whitelistSeeds().stream().noneMatch(seed -> seed.name().equals("chainsaw")));
		assertSame(without, without.removingRepairable("chainsaw"));
		assertSame(original, original.removingRepairable("  "));
		assertSame(original, original.removingRepairable(null));
	}

	@Test
	void whitelistMutationRoundtripsThroughSettingsFile(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("settings.json");
		var store = new RepairSettingsStore(file.toString());
		var updated = RepairSettings.defaults()
				.addingRepairable("pickaxe", (short) 9)
				.removingRepairable("repeater");
		store.save(updated);
		assertTrue(Files.isRegularFile(file));
		assertEquals(updated.whitelistSeeds(), store.load().orElseThrow().whitelistSeeds());
	}
}
