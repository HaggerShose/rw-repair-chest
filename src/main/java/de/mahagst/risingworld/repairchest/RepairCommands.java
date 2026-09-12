package de.mahagst.risingworld.repairchest;

import net.risingworld.api.World;
import net.risingworld.api.definitions.Objects;
import net.risingworld.api.events.EventMethod;
import net.risingworld.api.events.Listener;
import net.risingworld.api.events.player.PlayerCommandEvent;
import net.risingworld.api.events.player.PlayerDropItemFromStorageEvent;
import net.risingworld.api.events.player.PlayerObjectInteractionEvent;
import net.risingworld.api.events.player.PlayerSetSignTextEvent;
import net.risingworld.api.events.player.PlayerStorageAccessEvent;
import net.risingworld.api.events.player.inventory.PlayerInventoryToStorageEvent;
import net.risingworld.api.events.player.inventory.PlayerStorageToInventoryEvent;
import net.risingworld.api.events.player.world.PlayerChangeObjectInfoEvent;
import net.risingworld.api.objects.Player;
import net.risingworld.api.objects.Sign;
import net.risingworld.api.objects.Storage;
import net.risingworld.api.objects.world.ObjectElement;

/** Operator commands and station storage/sign events. */
public final class RepairCommands implements Listener {
	private final RepairService repairService;
	private final RepairSettingsStore store;

	public RepairCommands(RepairService repairService, RepairSettingsStore store) {
		this.repairService = repairService;
		this.store = store;
	}

	@EventMethod
	public void onCommand(PlayerCommandEvent event) {
		String[] args = event.getCommand().split(" ");
		if (args.length == 0) {
			return;
		}
		String cmd = args[0].toLowerCase();
		if (!isOurs(cmd)) {
			return;
		}
		Player player = event.getPlayer();
		if (!isAllowed(player)) {
			return;
		}
		event.setCancelled(true);
		switch (cmd) {
			case "/make-repair-chest" -> makeChest(player, args);
			case "/make-repair-sign" -> makeSign(player, args);
			case "/remove-repair-chest" -> withFocusedChest(player, repairService::remove);
			case "/repair-info" -> withFocusedChest(player, repairService::info);
			case "/reload-repair-chest" -> reload(player);
			default -> {
			}
		}
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

	private static boolean isOurs(String cmd) {
		return cmd.equals("/make-repair-chest")
				|| cmd.equals("/make-repair-sign")
				|| cmd.equals("/remove-repair-chest")
				|| cmd.equals("/repair-info")
				|| cmd.equals("/reload-repair-chest");
	}

	private boolean isAllowed(Player player) {
		if (player.isAdmin()) {
			return true;
		}
		String uid = player.getUID();
		return uid != null && repairService.settings().allowedUids().contains(uid);
	}

	private void makeChest(Player player, String[] args) {
		String name = parseName(args);
		if (name == null) {
			player.sendTextMessage(Messages.USAGE_MAKE_CHEST);
			return;
		}
		withFocusedChest(player, (p, object, storage) -> repairService.registerChest(p, object, storage, name));
	}

	private void makeSign(Player player, String[] args) {
		String name = parseName(args);
		if (name == null) {
			player.sendTextMessage(Messages.USAGE_MAKE_SIGN);
			return;
		}
		withFocusedSign(player, (p, object, sign) -> repairService.registerSign(p, object, sign, name));
	}

	private void withFocusedChest(Player player, FocusedChestHandler handler) {
		player.getObjectElementInLineOfSight(repairService.settings().interactDistance(), object -> {
			if (object == null) {
				player.sendTextMessage(Messages.NO_CHEST_IN_FOCUS);
				return;
			}
			Storage storage = World.getStorage(object.getGlobalID());
			if (storage == null) {
				player.sendTextMessage(Messages.NOT_A_STORAGE);
				return;
			}
			if (storage.isTransient()) {
				player.sendTextMessage(Messages.TRANSIENT_UNSUPPORTED);
				return;
			}
			handler.handle(player, object, storage);
		});
	}

	private void withFocusedSign(Player player, FocusedSignHandler handler) {
		player.getObjectElementInLineOfSight(repairService.settings().interactDistance(), object -> {
			if (object == null) {
				player.sendTextMessage(Messages.NO_SIGN_IN_FOCUS);
				return;
			}
			var def = object.getDefinition();
			if (def == null || def.type != Objects.Type.Sign) {
				player.sendTextMessage(Messages.NOT_A_SIGN);
				return;
			}
			Sign sign = World.getSign(object.getGlobalID());
			if (sign == null || !sign.isValid()) {
				player.sendTextMessage(Messages.NOT_A_SIGN);
				return;
			}
			handler.handle(player, object, sign);
		});
	}

	private void reload(Player player) {
		var loaded = store.load();
		if (loaded.isEmpty()) {
			player.sendTextMessage(Messages.SETTINGS_RELOAD_FAILED);
			return;
		}
		repairService.applySettings(loaded.get());
		player.sendTextMessage(Messages.settingsReloaded(repairService.settings().whitelistSeeds().size()));
	}

	private static String parseName(String[] args) {
		if (args.length < 2) {
			return null;
		}
		StringBuilder name = new StringBuilder();
		for (int i = 1; i < args.length; i++) {
			if (name.length() > 0) {
				name.append(' ');
			}
			name.append(args[i]);
		}
		String trimmed = name.toString().trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	@FunctionalInterface
	private interface FocusedChestHandler {
		void handle(Player player, ObjectElement object, Storage storage);
	}

	@FunctionalInterface
	private interface FocusedSignHandler {
		void handle(Player player, ObjectElement object, Sign sign);
	}
}
