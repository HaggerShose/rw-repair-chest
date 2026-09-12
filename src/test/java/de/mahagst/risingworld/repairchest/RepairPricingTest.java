package de.mahagst.risingworld.repairchest;

import static de.mahagst.risingworld.repairchest.RecipeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import net.risingworld.api.definitions.Items;

class RepairPricingTest {
	private static final RepairSettings SETTINGS = RepairSettings.defaults();

	@ParameterizedTest
	@CsvSource({"999, 1", "801, 4", "800, 4", "500, 8", "151, 14", "150, 16", "149, 16", "0, 16", "-1, 16"})
	void roundsMaterialsAndChargesFullPriceAtFifteenPercent(int durability, int expected) {
		var recipe = recipe(ingredient(470, "ironplate", 16));
		var needs = RepairPricing.quoteRecipe(recipe, durability, 1000, "bow1", SETTINGS).orElseThrow();
		assertEquals(1, needs.size());
		assertEquals(expected, needs.get(0).amount());
	}

	@Test
	void circuitboardOnlyForModernToolsInFullPriceBand() {
		var recipe = recipe(ingredient(470, "ironplate", 16), ingredient(145, "circuitboard", 1));
		var light = RepairPricing.quoteRecipe(recipe, 500, 1000, "miningdrill", SETTINGS).orElseThrow();
		assertEquals(1, light.size());
		assertEquals("ironplate", light.get(0).label());
		assertEquals(8, light.get(0).amount());

		var heavy = RepairPricing.quoteRecipe(recipe, 150, 1000, "miningdrill", SETTINGS).orElseThrow();
		assertEquals(2, heavy.size());
		assertEquals(16, heavy.get(0).amount());
		assertEquals("circuitboard", heavy.get(1).label());
		assertEquals(1, heavy.get(1).amount());

		var bow = RepairPricing.quoteRecipe(recipe, 500, 1000, "bow1", SETTINGS).orElseThrow();
		assertEquals(2, bow.size());
		assertEquals("circuitboard", bow.get(1).label());
	}

	@Test
	void usesExactDurabilityInsteadOfRoundedPercentage() {
		var recipe = recipe(ingredient(470, "ironplate", 1000));
		assertEquals(841, RepairPricing.quoteRecipe(recipe, 159, 1000, "bow1", SETTINGS).orElseThrow().get(0).amount());
		assertEquals(850, RepairPricing.quoteRecipe(recipe, 3751, 25000, "bow1", SETTINGS).orElseThrow().get(0).amount());
		assertEquals(1000, RepairPricing.quoteRecipe(recipe, 3750, 25000, "bow1", SETTINGS).orElseThrow().get(0).amount());
	}

	@Test
	void fullItemsHaveNoMaterialCost() {
		var recipe = recipe(ingredient(470, "ironplate", 16));
		assertTrue(RepairPricing.quoteRecipe(recipe, 1000, 1000, "bow1", SETTINGS).orElseThrow().isEmpty());
		assertTrue(RepairPricing.quoteRecipe(recipe, 1100, 1000, "bow1", SETTINGS).orElseThrow().isEmpty());
	}

	@Test
	void normalizesBatchRecipesAndCombinesIngredientsBeforeRounding() {
		var recipe = recipe(ingredient(470, "ironplate", 3), ingredient(470, "ironplate", 3));
		recipe.amount = 2;
		var half = RepairPricing.quoteRecipe(recipe, 500, 1000, "bow1", SETTINGS).orElseThrow();
		assertEquals(1, half.size());
		assertEquals(2, half.get(0).amount());
		assertEquals(3, RepairPricing.quoteRecipe(recipe, 150, 1000, "bow1", SETTINGS).orElseThrow().get(0).amount());
	}

	@Test
	void keepsReusableGroupTools() {
		var material = ingredient(470, "ironplate", 16);
		var tool = ingredient(0, "unused", 2);
		tool.itemDef = null;
		tool.group = Items.Group.Knife;
		tool.consume = false;
		var needs = RepairPricing.quoteRecipe(recipe(material, tool), 999, 1000, "bow1", SETTINGS).orElseThrow();
		assertTrue(needs.get(0).matches(item(470, 9, 1)));
		assertEquals(2, needs.get(1).amount());
		assertEquals(Items.Group.Knife, needs.get(1).group());
		assertFalse(needs.get(1).consume());
	}

	@Test
	void unusableRecipesCannotBecomeFreeRepairs() {
		assertTrue(RepairPricing.quoteRecipe(null, 500, 1000, "bow1", SETTINGS).isEmpty());
		assertTrue(RepairPricing.quoteRecipe(recipe(), 500, 1000, "bow1", SETTINGS).isEmpty());
		var recipe = recipe(ingredient(470, "ironplate", 16));
		recipe.amount = 0;
		assertTrue(RepairPricing.quoteRecipe(recipe, 500, 1000, "bow1", SETTINGS).isEmpty());
		recipe.amount = 1;
		assertTrue(RepairPricing.quoteRecipe(recipe, 500, 0, "bow1", SETTINGS).isEmpty());
		recipe.ingredients[0].itemDef = null;
		recipe.ingredients[0].group = Items.Group.None;
		assertTrue(RepairPricing.quoteRecipe(recipe, 500, 1000, "bow1", SETTINGS).isEmpty());
	}

	@Test
	void handlesLargeDurabilityWithoutOverflow() {
		var recipe = recipe(ingredient(470, "ironplate", Integer.MAX_VALUE));
		assertEquals(Integer.MAX_VALUE,
				RepairPricing.quoteRecipe(recipe, 0, Integer.MAX_VALUE, "bow1", SETTINGS).orElseThrow().get(0).amount());
		assertEquals(1,
				RepairPricing.quoteRecipe(recipe, Integer.MAX_VALUE - 1, Integer.MAX_VALUE, "bow1", SETTINGS)
						.orElseThrow().get(0).amount());
	}

	@ParameterizedTest
	@CsvSource({"999, 1", "500, 8", "150, 16", "0, 16"})
	void resolvesTargetVariantAndAddsFiveGoldToEveryRepair(int durability, int materialCost) {
		var targetDef = definition(134, "miningdrill");
		targetDef.durability = 1000;
		var target = item(134, 2, 1);
		when(target.getDefinition()).thenReturn(targetDef);
		when(target.getDurability()).thenReturn(durability);
		var recipe = recipe(ingredient(470, "ironplate", 16));
		var needs = RepairPricing.recipeFor(target, SETTINGS, (name, variant) -> {
			assertEquals("miningdrill", name);
			assertEquals(2, variant);
			return recipe;
		}, name -> {
			assertEquals(SETTINGS.goldItemName(), name);
			return definition(451, SETTINGS.goldItemName());
		}).orElseThrow();
		assertEquals(2, needs.size());
		assertEquals(materialCost, needs.get(0).amount());
		assertEquals(new RepairPricing.Need((short) 451, SETTINGS.goldFee(), SETTINGS.goldItemName(), true, null),
				needs.get(1));
	}

	@Test
	void fallsBackToDefaultRecipeWhenVariantLookupMisses() {
		var targetDef = definition(132, "chainsaw");
		targetDef.durability = 1000;
		var target = item(132, 3, 1);
		when(target.getDefinition()).thenReturn(targetDef);
		when(target.getDurability()).thenReturn(500);
		var recipe = recipe(ingredient(470, "ironplate", 16));
		var priced = RepairPricing.recipeFor(target, SETTINGS, (name, variant) -> {
			if (variant == 3) {
				return null;
			}
			return recipe;
		}, name -> definition(451, "goldingot")).orElseThrow();
		assertEquals(8, priced.get(0).amount());
		assertEquals(5, priced.get(1).amount());
	}

	@Test
	void missingRecipeMeansEmptyOptional() {
		var targetDef = definition(136, "trimmer");
		targetDef.durability = 1000;
		var target = item(136, 0, 1);
		when(target.getDefinition()).thenReturn(targetDef);
		when(target.getDurability()).thenReturn(500);
		assertTrue(RepairPricing.recipeFor(target, SETTINGS, (name, variant) -> null, name -> definition(451, "goldingot"))
				.isEmpty());
		assertTrue(RepairPricing.recipeFor(target, SETTINGS, (name, variant) -> recipe(), name -> definition(451, "goldingot"))
				.isEmpty());
	}

	@Test
	void usesManualRecipeWhenApiHasNone() {
		var targetDef = definition(250, "morningstar1");
		targetDef.durability = 1000;
		var target = item(250, 0, 1);
		when(target.getDefinition()).thenReturn(targetDef);
		when(target.getDurability()).thenReturn(500);
		var needs = RepairPricing.recipeFor(target, SETTINGS, (name, variant) -> null, name -> switch (name) {
			case "goldingot" -> definition(451, "goldingot");
			case "ironplate" -> definition(470, "ironplate");
			case "tungstenplate" -> definition(480, "tungstenplate");
			default -> null;
		}).orElseThrow();
		assertEquals(3, needs.size());
		assertEquals(new RepairPricing.Need((short) 470, 6, "ironplate", true, null), needs.get(0));
		assertEquals(new RepairPricing.Need((short) 480, 3, "tungstenplate", true, null), needs.get(1));
		assertEquals(new RepairPricing.Need((short) 451, 5, "goldingot", true, null), needs.get(2));
	}

	@Test
	void apiRecipeWinsOverManualRecipe() {
		var targetDef = definition(250, "morningstar1");
		targetDef.durability = 1000;
		var target = item(250, 0, 1);
		when(target.getDefinition()).thenReturn(targetDef);
		when(target.getDurability()).thenReturn(500);
		var api = recipe(ingredient(470, "ironplate", 4));
		var needs = RepairPricing.recipeFor(target, SETTINGS, (name, variant) -> api, name -> definition(451, "goldingot"))
				.orElseThrow();
		assertEquals(2, needs.size());
		assertEquals(2, needs.get(0).amount());
		assertEquals("ironplate", needs.get(0).label());
	}

	@Test
	void addsFeeOnTopOfRecipeGoldAndRejectsMissingDefinitions() {
		var targetDef = definition(134, "miningdrill");
		targetDef.durability = 1000;
		var target = item(134, 0, 1);
		when(target.getDefinition()).thenReturn(targetDef);
		when(target.getDurability()).thenReturn(500);
		var recipe = recipe(ingredient(451, "goldingot", 16));
		assertTrue(RepairPricing.recipeFor(target, SETTINGS, (name, variant) -> null, name -> null).isEmpty());
		assertTrue(RepairPricing.recipeFor(target, SETTINGS, (name, variant) -> recipe, name -> null).isEmpty());
		var gold = definition(451, "goldingot");
		var needs = RepairPricing.recipeFor(target, SETTINGS, (name, variant) -> recipe, name -> gold).orElseThrow();
		assertEquals(1, needs.size());
		assertEquals(13, needs.get(0).amount());
		when(target.getDurability()).thenReturn(1000);
		assertTrue(RepairPricing.recipeFor(target, SETTINGS, (name, variant) -> recipe, name -> gold).orElseThrow()
				.isEmpty());
	}

	@Test
	void allocatesAcrossStacksAndLeavesExcess() {
		var need = new RepairPricing.Need((short) 470, 8, "ironplate", true, null);
		var items = new net.risingworld.api.objects.Item[]{item(470, 0, 3), item(470, 1, 20), null, item(470, 0, 10)};
		var plan = RepairPricing.plan(items, null, java.util.List.of(need));
		assertTrue(plan.missing().isEmpty());
		assertEquals(java.util.List.of(new RepairPricing.Removal(0, 3), new RepairPricing.Removal(1, 5)), plan.removals());
	}

	@Test
	void retainsCatalystsAndNeverCountsTheRepairTarget() {
		var knife = item(100, 0, 1);
		var knifeDef = definition(100, "knife");
		knifeDef.group = Items.Group.Knife;
		when(knife.getDefinition()).thenReturn(knifeDef);
		var catalyst = new RepairPricing.Need((short) 0, 1, "any knife", false, Items.Group.Knife);
		assertEquals(java.util.List.of(catalyst),
				RepairPricing.plan(new net.risingworld.api.objects.Item[]{knife}, knife, java.util.List.of(catalyst))
						.missing());
		var plan = RepairPricing.plan(new net.risingworld.api.objects.Item[]{knife}, null, java.util.List.of(catalyst));
		assertTrue(plan.missing().isEmpty());
		assertTrue(plan.removals().isEmpty());
	}
}
