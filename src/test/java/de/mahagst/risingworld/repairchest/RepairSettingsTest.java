package de.mahagst.risingworld.repairchest;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepairSettingsTest {
	@Test
	void withWhitelistReplacesNamesAndKeepsOtherKnobs() {
		var original = RepairSettings.defaults();
		var names = List.of("pickaxe");
		var updated = original.withWhitelist(names);
		assertEquals(names, updated.whitelist());
		assertEquals(original.goldFee(), updated.goldFee());
		assertEquals(original.goldItemName(), updated.goldItemName());
		assertEquals(original.allowedChestTypes(), updated.allowedChestTypes());
		assertEquals(original.manualRecipes(), updated.manualRecipes());
		assertNotSame(original, updated);
	}

	@Test
	void addingRepairableAppendsUntilNameExists() {
		var original = RepairSettings.defaults();
		var added = original.addingRepairable("pickaxe");
		assertEquals(original.whitelist().size() + 1, added.whitelist().size());
		assertEquals("pickaxe", added.whitelist().get(added.whitelist().size() - 1));
		assertSame(added, added.addingRepairable("pickaxe"));
		assertSame(original, original.addingRepairable("  "));
		assertSame(original, original.addingRepairable(null));
	}

	@Test
	void removingRepairableDropsByName() {
		var original = RepairSettings.defaults();
		var without = original.removingRepairable("chainsaw");
		assertEquals(original.whitelist().size() - 1, without.whitelist().size());
		assertTrue(without.whitelist().stream().noneMatch(name -> name.equals("chainsaw")));
		assertSame(without, without.removingRepairable("chainsaw"));
		assertSame(original, original.removingRepairable("  "));
		assertSame(original, original.removingRepairable(null));
	}

	@Test
	void whitelistMutationRoundtripsThroughSettingsFile(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("settings.json");
		var store = new RepairSettingsStore(file.toString());
		var updated = RepairSettings.defaults()
				.addingRepairable("pickaxe")
				.removingRepairable("repeater");
		store.save(updated);
		assertTrue(Files.isRegularFile(file));
		assertEquals(updated.whitelist(), store.load().orElseThrow().whitelist());
	}
}
