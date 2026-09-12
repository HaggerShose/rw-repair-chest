package de.mahagst.risingworld.repairchest.listener;

import de.mahagst.risingworld.repairchest.message.Messages;
import de.mahagst.risingworld.repairchest.repair.RepairService;

import net.risingworld.api.events.EventMethod;
import net.risingworld.api.events.Listener;
import net.risingworld.api.events.player.PlayerDropItemFromStorageEvent;
import net.risingworld.api.events.player.PlayerObjectInteractionEvent;
import net.risingworld.api.events.player.PlayerSetSignTextEvent;
import net.risingworld.api.events.player.PlayerStorageAccessEvent;
import net.risingworld.api.events.player.inventory.PlayerInventoryToStorageEvent;
import net.risingworld.api.events.player.inventory.PlayerStorageToInventoryEvent;
import net.risingworld.api.events.player.world.PlayerChangeObjectInfoEvent;
import net.risingworld.api.objects.world.ObjectElement;

/** Storage and sign events for registered repair stations. */
public final class RepairListener implements Listener {
	private final RepairService repairService;

	public RepairListener(RepairService repairService) {
		this.repairService = repairService;
	}

	@EventMethod
	public void onInventoryToStorage(PlayerInventoryToStorageEvent event) {
		if (event.isCancelled()) {
			return;
		}
		if (repairService.isRepairing(event.getStorageID())) {
			event.setCancelled(true);
			return;
		}
		repairService.onPut(event.getStorage(), event.getPlayer());
	}

	@EventMethod
	public void onStorageToInventory(PlayerStorageToInventoryEvent event) {
		if (event.isCancelled()) {
			return;
		}
		if (repairService.isRepairing(event.getStorageID())) {
			event.setCancelled(true);
			return;
		}
		repairService.onTake(event.getStorage(), event.getPlayer(), event.getItem());
	}

	@EventMethod
	public void onDropFromStorage(PlayerDropItemFromStorageEvent event) {
		if (event.isCancelled()) {
			return;
		}
		if (repairService.isRepairing(event.getStorageID())) {
			event.setCancelled(true);
			return;
		}
		repairService.onTake(event.getStorage(), event.getPlayer(), event.getItem());
	}

	@EventMethod
	public void onStorageAccess(PlayerStorageAccessEvent event) {
		if (repairService.isRepairing(event.getStorageID())) {
			event.setCancelled(true);
		}
	}

	@EventMethod
	public void onChangeObjectInfo(PlayerChangeObjectInfoEvent event) {
		ObjectElement object = event.getObject();
		if (object == null) {
			return;
		}
		if (repairService.isRepairing(object.getGlobalID())) {
			event.setNewInfoID(RepairService.LOCK_INFO);
		}
	}

	@EventMethod
	public void onSetSignText(PlayerSetSignTextEvent event) {
		if (repairService.isStationSign(event.getSignID())) {
			event.setCancelled(true);
		}
	}

	@EventMethod
	public void onObjectInteraction(PlayerObjectInteractionEvent event) {
		if (!repairService.isStationSign(event.getGlobalID())) {
			return;
		}
		event.setCancelled(true);
		if (repairService.isIdleSign(event.getGlobalID())) {
			event.getPlayer().sendTextMessage(Messages.PUT_DAMAGED_ITEM);
		}
	}
}
