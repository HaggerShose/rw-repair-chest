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
	private static final int FULL_PRICE_PERCENT = SETTINGS.fullPriceRemainingPercent();

	@ParameterizedTest
	@CsvSource({"999, 1", "801, 4", "800, 4", "500, 8", "151, 14", "150, 16", "149, 16", "0, 16", "-1, 16"})
	void roundsEachMaterialUpAndChargesFullPriceAtFifteenPercent(int durability, int expected) {
		var recipe = recipe(ingredient(470, "ironplate", 16), ingredient(145, "circuitboard", 1));
		var needs = RepairPricing.quoteRecipe(recipe, durability, 1000, FULL_PRICE_PERCENT).orElseThrow();
		assertEquals(expected, needs.get(0).amount());
		assertEquals(1, needs.get(1).amount());
	}

	@Test
	void usesExactDurabilityInsteadOfRoundedPercentage() {
		var recipe = recipe(ingredient(470, "ironplate", 1000));
		assertEquals(841, RepairPricing.quoteRecipe(recipe, 159, 1000, FULL_PRICE_PERCENT).orElseThrow().get(0).amount());
		assertEquals(850, RepairPricing.quoteRecipe(recipe, 3751, 25000, FULL_PRICE_PERCENT).orElseThrow().get(0).amount());
		assertEquals(1000, RepairPricing.quoteRecipe(recipe, 3750, 25000, FULL_PRICE_PERCENT).orElseThrow().get(0).amount());
	}

	@Test
	void fullItemsHaveNoMaterialCost() {
		var recipe = recipe(ingredient(470, "ironplate", 16));
		assertTrue(RepairPricing.quoteRecipe(recipe, 1000, 1000, FULL_PRICE_PERCENT).orElseThrow().isEmpty());
		assertTrue(RepairPricing.quoteRecipe(recipe, 1100, 1000, FULL_PRICE_PERCENT).orElseThrow().isEmpty());
	}

	@Test
	void normalizesBatchRecipesAndCombinesIngredientsBeforeRounding() {
		var recipe = recipe(ingredient(470, "ironplate", 3), ingredient(470, "ironplate", 3));
		recipe.amount = 2;
		var half = RepairPricing.quoteRecipe(recipe, 500, 1000, FULL_PRICE_PERCENT).orElseThrow();
		assertEquals(1, half.size());
		assertEquals(2, half.get(0).amount());
		assertEquals(3, RepairPricing.quoteRecipe(recipe, 150, 1000, FULL_PRICE_PERCENT).orElseThrow().get(0).amount());
	}

	@Test
	void keepsReusableGroupTools() {
		var material = ingredient(470, "ironplate", 16);
		var tool = ingredient(0, "unused", 2);
		tool.itemDef = null;
		tool.group = Items.Group.Knife;
		tool.consume = false;
		var needs = RepairPricing.quoteRecipe(recipe(material, tool), 999, 1000, FULL_PRICE_PERCENT).orElseThrow();
		assertTrue(needs.get(0).matches(item(470, 9, 1)));
		assertEquals(2, needs.get(1).amount());
		assertEquals(Items.Group.Knife, needs.get(1).group());
		assertFalse(needs.get(1).consume());
	}

	@Test
	void unusableRecipesCannotBecomeFreeRepairs() {
		assertTrue(RepairPricing.quoteRecipe(null, 500, 1000, FULL_PRICE_PERCENT).isEmpty());
		assertTrue(RepairPricing.quoteRecipe(recipe(), 500, 1000, FULL_PRICE_PERCENT).isEmpty());
		var recipe = recipe(ingredient(470, "ironplate", 16));
		recipe.amount = 0;
		assertTrue(RepairPricing.quoteRecipe(recipe, 500, 1000, FULL_PRICE_PERCENT).isEmpty());
		recipe.amount = 1;
		assertTrue(RepairPricing.quoteRecipe(recipe, 500, 0, FULL_PRICE_PERCENT).isEmpty());
		recipe.ingredients[0].itemDef = null;
		recipe.ingredients[0].group = Items.Group.None;
		assertTrue(RepairPricing.quoteRecipe(recipe, 500, 1000, FULL_PRICE_PERCENT).isEmpty());
	}

	@Test
	void handlesLargeDurabilityWithoutOverflow() {
		var recipe = recipe(ingredient(470, "ironplate", Integer.MAX_VALUE));
		assertEquals(Integer.MAX_VALUE,
				RepairPricing.quoteRecipe(recipe, 0, Integer.MAX_VALUE, FULL_PRICE_PERCENT).orElseThrow().get(0).amount());
		assertEquals(1,
				RepairPricing.quoteRecipe(recipe, Integer.MAX_VALUE - 1, Integer.MAX_VALUE, FULL_PRICE_PERCENT)
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
