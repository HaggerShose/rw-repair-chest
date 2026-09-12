package de.mahagst.risingworld.repairchest;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepairSettingsStoreTest {
	@Test
	void roundtripsDefaultsThroughJson() {
		var original = RepairSettings.defaults();
		var parsed = RepairSettingsStore.parse(toJson(original)).orElseThrow();
		assertEquals(original, parsed);
	}

	@Test
	void corruptJsonIsRejected() {
		assertTrue(RepairSettingsStore.parse("{").isEmpty());
		assertTrue(RepairSettingsStore.parse("not json").isEmpty());
	}

	@Test
	void missingConsumeDefaultsToTrue() {
		var parsed = RepairSettingsStore.parse("""
				{
				  "manualRecipes": [
				    {
				      "targetName": "morningstar1",
				      "ingredients": [
				        { "itemName": "ironplate", "count": 12 }
				      ]
				    }
				  ]
				}
				""").orElseThrow();
		assertEquals(1, parsed.manualRecipes().size());
		assertTrue(parsed.manualRecipes().get(0).ingredients().get(0).consume());
	}

	@Test
	void negativeValuesRejectTheFile() {
		assertTrue(RepairSettingsStore.parse("{\"debounceSeconds\": -1}").isEmpty());
		assertTrue(RepairSettingsStore.parse("{\"interactDistance\": -5}").isEmpty());
		assertTrue(RepairSettingsStore.parse("{\"goldFee\": -1}").isEmpty());
		assertTrue(RepairSettingsStore.parse("{\"fullPriceRemainingPercent\": 101}").isEmpty());
		assertTrue(RepairSettingsStore.parse("{\"goldItemName\": \"   \"}").isEmpty());
	}

	@Test
	void missingFieldsKeepDefaultsAndSkipBrokenListRows() {
		var parsed = RepairSettingsStore.parse("""
				{
				  "debounceSeconds": 3.5,
				  "whitelist": [
				    { "name": "chainsaw", "fallbackTypeId": 132 },
				    { "fallbackTypeId": 1 },
				    { "name": "  " }
				  ]
				}
				""").orElseThrow();
		var defaults = RepairSettings.defaults();
		assertEquals(3.5f, parsed.debounceSeconds());
		assertEquals(defaults.goldFee(), parsed.goldFee());
		assertEquals(defaults.goldItemName(), parsed.goldItemName());
		assertEquals(defaults.allowedChestTypes(), parsed.allowedChestTypes());
		assertEquals(1, parsed.whitelistSeeds().size());
		assertEquals("chainsaw", parsed.whitelistSeeds().get(0).name());
		assertEquals((short) 132, parsed.whitelistSeeds().get(0).fallbackTypeId());
	}

	@Test
	void createsDefaultsFileThenLoadsIt(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("settings.json");
		var store = new RepairSettingsStore(file.toString());
		assertEquals(RepairSettings.defaults(), store.loadOrCreate());
		assertTrue(Files.isRegularFile(file));
		assertFalse(Files.exists(file.resolveSibling("settings.json.tmp")));
		assertEquals(RepairSettings.defaults(), store.load().orElseThrow());
	}

	@Test
	void loadKeepsGoingWhenFileIsCorrupt(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("settings.json");
		Files.writeString(file, "{");
		var store = new RepairSettingsStore(file.toString());
		assertTrue(store.load().isEmpty());
		assertEquals("{", Files.readString(file));
	}

	private static String toJson(RepairSettings settings) {
		return new com.google.gson.Gson().toJson(RepairSettingsStore.FileDto.from(settings));
	}
}
