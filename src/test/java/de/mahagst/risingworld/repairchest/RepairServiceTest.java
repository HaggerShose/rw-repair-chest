package de.mahagst.risingworld.repairchest;

import static de.mahagst.risingworld.repairchest.RecipeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.risingworld.api.objects.Item;
import net.risingworld.api.objects.Storage;

class RepairServiceTest {
	@Test
	void restoresDurabilityBeforeConsumingRecipeMaterialsAndGold() {
		Item target = item(134, 0, 1);
		Storage storage = mock(Storage.class);
		var materials = RepairPricing.plan(new Item[]{target, item(470, 0, 20), item(451, 0, 10)}, target,
				List.of(new RepairPricing.Need((short) 470, 8, "ironplate", true, null),
						new RepairPricing.Need((short) 451, 5, "goldingot", true, null)));
		RepairService.repairAndConsume(target, 25000, storage, materials);
		var order = inOrder(target, storage);
		order.verify(target).setDurability(25000);
		order.verify(storage).removeItem(1, 8);
		order.verify(storage).removeItem(2, 5);
		verifyNoMoreInteractions(storage);
	}

	@Test
	void failureToRepairDoesNotConsumeAnything() {
		Item target = mock(Item.class);
		Storage storage = mock(Storage.class);
		doThrow(new IllegalStateException("Cannot set durability")).when(target).setDurability(1000);
		var materials = new RepairPricing.Plan(List.of(), List.of(new RepairPricing.Removal(1, 5)));
		assertThrows(IllegalStateException.class, () -> RepairService.repairAndConsume(target, 1000, storage, materials));
		verifyNoInteractions(storage);
	}

	@Test
	void incompleteMaterialsDoNotRepairOrConsume() {
		Item target = mock(Item.class);
		Storage storage = mock(Storage.class);
		var missing = new RepairPricing.Need((short) 451, 5, "goldingot", true, null);
		var materials = new RepairPricing.Plan(List.of(missing), List.of());
		assertThrows(IllegalArgumentException.class, () -> RepairService.repairAndConsume(target, 1000, storage, materials));
		verifyNoInteractions(target, storage);
	}
}
