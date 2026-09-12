package de.mahagst.risingworld.repairchest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.risingworld.api.definitions.Crafting;
import net.risingworld.api.definitions.Items;
import net.risingworld.api.objects.Item;

final class RecipeFixtures {
	private RecipeFixtures() {
	}

	static Items.ItemDefinition definition(int id, String name) {
		var def = mock(Items.ItemDefinition.class);
		def.id = (short) id;
		def.name = name;
		return def;
	}

	static Crafting.Recipe.Ingredient ingredient(int id, String name, int count) {
		var ingredient = mock(Crafting.Recipe.Ingredient.class);
		ingredient.itemDef = definition(id, name);
		ingredient.count = count;
		ingredient.consume = true;
		return ingredient;
	}

	static Crafting.Recipe recipe(Crafting.Recipe.Ingredient... ingredients) {
		var recipe = mock(Crafting.Recipe.class);
		recipe.amount = 1;
		recipe.ingredients = ingredients;
		return recipe;
	}

	static Item item(int id, int variant, int stack) {
		Item item = mock(Item.class);
		when(item.getTypeID()).thenReturn((short) id);
		when(item.getVariant()).thenReturn(variant);
		when(item.getStack()).thenReturn(stack);
		return item;
	}
}
