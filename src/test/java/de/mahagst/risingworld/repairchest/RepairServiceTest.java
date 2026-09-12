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
		Item plates = item(470, 0, 20);
		Item gold = item(451, 0, 10);
		Storage storage = mock(Storage.class);
		when(storage.getItem(1)).thenReturn(plates);
		when(storage.getItem(2)).thenReturn(gold);
		var materials = RepairPricing.plan(new Item[]{target, plates, gold}, target,
				List.of(new RepairPricing.Need((short) 470, 8, "ironplate", true, null),
						new RepairPricing.Need((short) 451, 5, "goldingot", true, null)));
		RepairService.repairAndConsume(target, 25000, storage, materials);
		var order = inOrder(target, storage);
		order.verify(target).setDurability(25000);
		order.verify(storage).getItem(1);
		order.verify(storage).removeItem(1, 8);
		order.verify(storage).getItem(2);
		order.verify(storage).removeItem(2, 5);
		verifyNoMoreInteractions(storage);
	}

	@Test
	void failureToRepairDoesNotConsumeAnything() {
		Item target = mock(Item.class);
		Storage storage = mock(Storage.class);
		doThrow(new IllegalStateException("Cannot set durability")).when(target).setDurability(1000);
		var materials = new RepairPricing.Plan(List.of(), List.of(new RepairPricing.Removal(1, 5, (short) 451)));
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

	@Test
	void refusesToRemoveWhenSlotChangedAndStopsFurtherRemovals() {
		Item target = item(134, 0, 1);
		Item unexpected = item(55, 0, 1);
		Item gold = item(451, 0, 10);
		Storage storage = mock(Storage.class);
		when(storage.getItem(1)).thenReturn(unexpected);
		when(storage.getItem(2)).thenReturn(gold);
		var materials = new RepairPricing.Plan(List.of(), List.of(
				new RepairPricing.Removal(1, 8, (short) 470),
				new RepairPricing.Removal(2, 5, (short) 451)));
		RepairService.repairAndConsume(target, 1000, storage, materials);
		verify(target).setDurability(1000);
		verify(storage).getItem(1);
		verify(storage, never()).removeItem(anyInt(), anyInt());
	}

	@Test
	void refusesToConsumeDurableItemsAtRemovalTime() {
		Item target = item(134, 0, 1);
		Item sickle = item(55, 0, 1);
		var sickleDef = definition(55, "sickle");
		sickleDef.durability = 500;
		when(sickle.getDefinition()).thenReturn(sickleDef);
		Storage storage = mock(Storage.class);
		when(storage.getItem(1)).thenReturn(sickle);
		var materials = new RepairPricing.Plan(List.of(),
				List.of(new RepairPricing.Removal(1, 1, (short) 55)));
		RepairService.repairAndConsume(target, 1000, storage, materials);
		verify(target).setDurability(1000);
		verify(storage).getItem(1);
		verify(storage, never()).removeItem(anyInt(), anyInt());
	}
}
