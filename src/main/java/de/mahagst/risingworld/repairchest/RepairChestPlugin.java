package de.mahagst.risingworld.repairchest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.risingworld.api.Plugin;
import net.risingworld.api.Server;
import net.risingworld.api.Timer;
import net.risingworld.api.World;
import net.risingworld.api.database.Database;
import net.risingworld.api.definitions.Definitions;
import net.risingworld.api.definitions.Items;
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
import net.risingworld.api.objects.Item;
import net.risingworld.api.objects.Player;
import net.risingworld.api.objects.Sign;
import net.risingworld.api.objects.Storage;
import net.risingworld.api.objects.world.ObjectElement;
import net.risingworld.api.ui.style.TextAnchor;
import net.risingworld.api.utils.Vector3f;

/**
 * Admin-registered named repair stations: put a damaged whitelisted item,
 * quote materials on a linked sign, lock briefly, restore durability, consume mats.
 */
public class RepairChestPlugin extends Plugin implements Listener {
	static final float LOS_DISTANCE = 5f;
	static final float MAX_IDENTITY_DISTANCE = 5f;
	static final float DEBOUNCE_SECONDS = 2f;
	/** Short delay so storage reflects a take before scanning done->idle. */
	static final float POST_TAKE_SCAN_SECONDS = 0.25f;
	static final float REPAIR_SECONDS = 2f;
	static final long LOCK_INFO = 1L;
	static final long UNLOCK_INFO = 0L;
	static final short FALLBACK_DRILL_ID = 134;
	static final short FALLBACK_CHAINSAW_ID = 132;
	static final short FALLBACK_TRIMMER_ID = 136;
	static final short FALLBACK_BOW1_ID = 195;
	static final short FALLBACK_CROSSBOW_ID = 205;
	static final short FALLBACK_REPEATER_ID = 300;
	static final short FALLBACK_MORNINGSTAR_ID = 250;
	static final String KIND_ITEM = "item";
	static final String KIND_OBJECT = "object";
	static final String KIND_CONSTRUCTION = "construction";
	static final String KIND_CLOTHING = "clothing";

	/** Allowed chest object definition names (definitions.db objects.name). */
	private static final Set<String> ALLOWED_CHEST_TYPES = Set.of(
			"skullchest",
			"goldchest",
			"silverchest",
			"armoredchest");

	private static final Set<String> ALLOWED_UIDS = Set.of(
			"76561198002368372");

	private Database database;
	private RepairRepository repository;
	/** Membership + current state; SQLite remains source of truth. */
	private final Map<Long, RepairStation> stationsByStorageId = new HashMap<>();
	private final Map<Long, Long> signIdToStorageId = new HashMap<>();
	private final Map<Long, Timer> debounceTimers = new HashMap<>();
	private final Map<Long, Timer> repairTimers = new HashMap<>();
	private final Map<Long, String> lastActorUid = new HashMap<>();
	private List<RepairRepository.WhitelistEntry> whitelist = List.of();

	@Override
	public void onEnable() {
		database = getSQLiteConnection(getPath() + "/repair.db");
		if (database == null) {
			System.out.println("[RepairChest] Failed to open SQLite database");
			return;
		}
		repository = new RepairRepository(database);
		repository.createSchema();
		repository.seedWhitelistItem(resolveItemId("miningdrill", FALLBACK_DRILL_ID), "miningdrill");
		repository.seedWhitelistItem(resolveItemId("chainsaw", FALLBACK_CHAINSAW_ID), "chainsaw");
		repository.seedWhitelistItem(resolveItemId("trimmer", FALLBACK_TRIMMER_ID), "trimmer");
		repository.seedWhitelistItem(resolveItemId("bow1", FALLBACK_BOW1_ID), "bow1");
		repository.seedWhitelistItem(resolveItemId("crossbow", FALLBACK_CROSSBOW_ID), "crossbow");
		repository.seedWhitelistItem(resolveItemId("repeater", FALLBACK_REPEATER_ID), "repeater");
		repository.seedWhitelistItem(resolveItemId("morningstar1", FALLBACK_MORNINGSTAR_ID), "morningstar1");
		whitelist = repository.findWhitelist();
		loadStations();
		sweepAndResetIdle();
		registerEventListener(this);
		System.out.println("[RepairChest] enabled");
	}

	@Override
	public void onDisable() {
		cancelAllTimers();
		if (database != null) {
			database.execute("PRAGMA wal_checkpoint(TRUNCATE)");
			database.close();
		}
		System.out.println("[RepairChest] disabled");
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
			case "/remove-repair-chest" -> withFocusedChest(player, this::remove);
			case "/repair-info" -> withFocusedChest(player, this::info);
			default -> {
			}
		}
	}

	@EventMethod
	public void onInventoryToStorage(PlayerInventoryToStorageEvent event) {
		if (event.isCancelled()) {
			return;
		}
		RepairStation station = stationsByStorageId.get(event.getStorageID());
		if (station != null && station.isRepairing()) {
			event.setCancelled(true);
			return;
		}
		onPut(event.getStorage(), event.getPlayer());
	}

	@EventMethod
	public void onStorageToInventory(PlayerStorageToInventoryEvent event) {
		if (event.isCancelled()) {
			return;
		}
		RepairStation station = stationsByStorageId.get(event.getStorageID());
		if (station != null && station.isRepairing()) {
			event.setCancelled(true);
			return;
		}
		onTake(event.getStorage(), event.getPlayer(), event.getItem());
	}

	@EventMethod
	public void onDropFromStorage(PlayerDropItemFromStorageEvent event) {
		if (event.isCancelled()) {
			return;
		}
		RepairStation station = stationsByStorageId.get(event.getStorageID());
		if (station != null && station.isRepairing()) {
			event.setCancelled(true);
			return;
		}
		onTake(event.getStorage(), event.getPlayer(), event.getItem());
	}

	@EventMethod
	public void onStorageAccess(PlayerStorageAccessEvent event) {
		RepairStation station = stationsByStorageId.get(event.getStorageID());
		if (station != null && station.isRepairing()) {
			event.setCancelled(true);
		}
	}

	@EventMethod
	public void onChangeObjectInfo(PlayerChangeObjectInfoEvent event) {
		ObjectElement object = event.getObject();
		if (object == null) {
			return;
		}
		RepairStation station = stationsByStorageId.get(object.getGlobalID());
		if (station != null && station.isRepairing()) {
			event.setNewInfoID(LOCK_INFO);
		}
	}

	@EventMethod
	public void onSetSignText(PlayerSetSignTextEvent event) {
		if (signIdToStorageId.containsKey(event.getSignID())) {
			event.setCancelled(true);
		}
	}

	@EventMethod
	public void onObjectInteraction(PlayerObjectInteractionEvent event) {
		Long storageId = signIdToStorageId.get(event.getGlobalID());
		if (storageId == null) {
			return;
		}
		event.setCancelled(true);
		RepairStation station = stationsByStorageId.get(storageId);
		if (station != null && station.isIdle()) {
			event.getPlayer().sendTextMessage(Messages.PUT_DAMAGED_ITEM);
		}
	}

	private static boolean isOurs(String cmd) {
		return cmd.equals("/make-repair-chest")
				|| cmd.equals("/make-repair-sign")
				|| cmd.equals("/remove-repair-chest")
				|| cmd.equals("/repair-info");
	}

	private static boolean isAllowed(Player player) {
		if (player.isAdmin()) {
			return true;
		}
		String uid = player.getUID();
		return uid != null && ALLOWED_UIDS.contains(uid);
	}

	private void makeChest(Player player, String[] args) {
		String name = parseName(args);
		if (name == null) {
			player.sendTextMessage(Messages.USAGE_MAKE_CHEST);
			return;
		}
		withFocusedChest(player, (p, object, storage) -> registerChest(p, object, storage, name));
	}

	private void makeSign(Player player, String[] args) {
		String name = parseName(args);
		if (name == null) {
			player.sendTextMessage(Messages.USAGE_MAKE_SIGN);
			return;
		}
		withFocusedSign(player, (p, object, sign) -> registerSign(p, object, sign, name));
	}

	private void registerChest(Player player, ObjectElement object, Storage storage, String name) {
		var def = object.getDefinition();
		if (def == null || def.type != Objects.Type.Storage) {
			player.sendTextMessage(Messages.NOT_STORAGE_CHEST);
			return;
		}
		String typeName = objectType(object);
		if (!ALLOWED_CHEST_TYPES.contains(typeName)) {
			player.sendTextMessage(Messages.chestTypeNotAllowed(typeName, object.getTypeID()));
			return;
		}
		if (stationsByStorageId.containsKey(storage.getID())) {
			player.sendTextMessage(Messages.CHEST_ALREADY_REGISTERED);
			return;
		}
		if (repository.findByName(name).isPresent()) {
			player.sendTextMessage(Messages.NAME_IN_USE);
			return;
		}
		var pos = object.getWorldPosition();
		RepairStation station = new RepairStation(
				name,
				storage.getID(),
				object.getGlobalID(),
				object.getChunkPositionX(),
				object.getChunkPositionY(),
				object.getChunkPositionZ(),
				pos.x,
				pos.y,
				pos.z,
				objectType(object),
				storage.getCreationDate(),
				null, null, null, null, null,
				null, null, null, null, null,
				RepairStation.IDLE,
				System.currentTimeMillis());
		if (!repository.insert(station)) {
			player.sendTextMessage(Messages.SAVE_FAILED);
			return;
		}
		putStation(station);
		player.sendTextMessage(Messages.chestCreated(name));
	}

	private void registerSign(Player player, ObjectElement object, Sign sign, String name) {
		var existing = repository.findByName(name);
		if (existing.isEmpty()) {
			player.sendTextMessage(Messages.NO_CHEST_WITH_NAME);
			return;
		}
		RepairStation station = existing.get();
		if (!verifyOrDrop(station)) {
			player.sendTextMessage(Messages.CHEST_INVALID);
			return;
		}
		station = stationsByStorageId.get(station.storageId());
		if (station == null) {
			player.sendTextMessage(Messages.CHEST_INVALID);
			return;
		}
		if (station.hasSign()) {
			player.sendTextMessage(Messages.STATION_HAS_SIGN);
			return;
		}
		long signId = sign.getID();
		if (signIdToStorageId.containsKey(signId)) {
			player.sendTextMessage(Messages.SIGN_ALREADY_LINKED);
			return;
		}
		var pos = object.getWorldPosition();
		RepairStation linked = station.withSign(
				signId,
				object.getGlobalID(),
				object.getChunkPositionX(),
				object.getChunkPositionY(),
				object.getChunkPositionZ(),
				pos.x,
				pos.y,
				pos.z,
				objectType(object),
				object.getCreationDate());
		repository.linkSign(linked);
		putStation(linked);
		updateSign(linked, Messages.READY);
		player.sendTextMessage(Messages.signLinked(name));
	}

	private void remove(Player player, ObjectElement object, Storage storage) {
		RepairStation station = stationsByStorageId.get(storage.getID());
		if (station == null) {
			player.sendTextMessage(Messages.CHEST_NOT_REGISTERED);
			return;
		}
		drop(storage.getID());
		player.sendTextMessage(Messages.CHEST_REMOVED);
	}

	private void info(Player player, ObjectElement object, Storage storage) {
		RepairStation station = requireValid(player, storage);
		if (station == null) {
			return;
		}
		boolean pending = debounceTimers.containsKey(station.storageId());
		StringBuilder white = new StringBuilder();
		for (RepairRepository.WhitelistEntry entry : whitelist) {
			if (white.length() > 0) {
				white.append(", ");
			}
			white.append(entry.label() != null ? entry.label() : Short.toString(entry.typeId()));
		}
		player.sendTextMessage(Messages.info(
				station.name(),
				station.objectType(),
				object.getTypeID(),
				station.state(),
				pending,
				station.hasSign(),
				white.isEmpty() ? "-" : white.toString()));
	}

	private RepairStation requireValid(Player player, Storage storage) {
		RepairStation station = stationsByStorageId.get(storage.getID());
		if (station == null) {
			player.sendTextMessage(Messages.CHEST_NOT_REGISTERED);
			return null;
		}
		if (!verifyOrDrop(station)) {
			player.sendTextMessage(Messages.CHEST_INVALID);
			return null;
		}
		return stationsByStorageId.get(storage.getID());
	}

	private void withFocusedChest(Player player, FocusedChestHandler handler) {
		player.getObjectElementInLineOfSight(LOS_DISTANCE, object -> {
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
		player.getObjectElementInLineOfSight(LOS_DISTANCE, object -> {
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

	private void onPut(Storage storage, Player player) {
		if (storage == null) {
			return;
		}
		RepairStation station = stationsByStorageId.get(storage.getID());
		if (station == null || station.isRepairing()) {
			return;
		}
		rememberActor(storage.getID(), player);
		restartDebounce(storage.getID());
	}

	private void onTake(Storage storage, Player player, Item taken) {
		if (storage == null) {
			return;
		}
		RepairStation station = stationsByStorageId.get(storage.getID());
		if (station == null || station.isRepairing()) {
			return;
		}
		rememberActor(storage.getID(), player);
		// After repair: taking the repaired whitelist item ends the cycle.
		// Do not scan storage immediately -- the item is often still listed in the event frame.
		if (station.isDone()) {
			cancelDebounce(storage.getID());
			if (!verifyOrDrop(station)) {
				return;
			}
			station = stationsByStorageId.get(storage.getID());
			if (station == null) {
				return;
			}
			if (taken != null && isWhitelisted(taken)) {
				setState(station, RepairStation.IDLE);
				notifyStation(stationsByStorageId.get(storage.getID()), player, Messages.READY);
				return;
			}
			restartDebounce(storage.getID(), POST_TAKE_SCAN_SECONDS);
			return;
		}
		restartDebounce(storage.getID(), DEBOUNCE_SECONDS);
	}

	private void restartDebounce(long storageId) {
		restartDebounce(storageId, DEBOUNCE_SECONDS);
	}

	private void restartDebounce(long storageId, float delaySeconds) {
		cancelDebounce(storageId);
		float delay = Math.max(delaySeconds, 0.1f);
		Timer timer = new Timer(1f, delay, 0, () -> enqueue(() -> onDebounceDue(storageId)));
		debounceTimers.put(storageId, timer);
		timer.start();
	}

	private void onDebounceDue(long storageId) {
		debounceTimers.remove(storageId);
		RepairStation station = stationsByStorageId.get(storageId);
		if (station == null) {
			return;
		}
		if (station.isRepairing()) {
			return;
		}
		if (!verifyOrDrop(station)) {
			return;
		}
		station = stationsByStorageId.get(storageId);
		if (station == null) {
			return;
		}
		scan(station);
	}

	private void scan(RepairStation station) {
		Storage storage = World.getStorage(station.storageId());
		if (storage == null || storage.isTransient()) {
			drop(station.storageId());
			return;
		}
		Player player = actor(station.storageId());
		Item[] items = storage.getItems();
		if (station.isDone()) {
			if (countWhitelisted(items, false) == 0) {
				setState(station, RepairStation.IDLE);
				notifyStation(stationsByStorageId.get(station.storageId()), player, Messages.READY);
			}
			return;
		}
		boolean wasIdle = station.isIdle();
		int any = countWhitelisted(items, false);
		int damaged = countWhitelisted(items, true);
		// Idle stays passive unless exactly one damaged whitelist item is present.
		if (wasIdle && !(any == 1 && damaged == 1)) {
			return;
		}
		if (any == 0) {
			setState(station, RepairStation.IDLE);
			notifyStation(stationsByStorageId.get(station.storageId()), player, Messages.READY);
			return;
		}
		if (any > 1) {
			setState(station, RepairStation.QUOTED);
			notifyStation(stationsByStorageId.get(station.storageId()), player, Messages.ONE_AT_A_TIME);
			return;
		}
		// Exactly one whitelisted item.
		if (damaged == 0) {
			setState(station, RepairStation.QUOTED);
			notifyStation(stationsByStorageId.get(station.storageId()), player, Messages.ALREADY_FULL);
			return;
		}
		Item target = firstWhitelisted(items, true);
		if (target == null) {
			setState(station, RepairStation.IDLE);
			notifyStation(stationsByStorageId.get(station.storageId()), player, Messages.READY);
			return;
		}
		List<RepairPricing.Need> recipe = RepairPricing.recipeFor(target);
		List<RepairPricing.Need> missing = missingNeeds(items, recipe);
		setState(station, RepairStation.QUOTED);
		station = stationsByStorageId.get(station.storageId());
		if (!missing.isEmpty()) {
			notifyStation(station, player, Messages.needs(missing), TextAnchor.UpperLeft);
			return;
		}
		beginRepair(station, player);
	}

	private void beginRepair(RepairStation station, Player player) {
		long storageId = station.storageId();
		cancelDebounce(storageId);
		setState(station, RepairStation.REPAIRING);
		station = stationsByStorageId.get(storageId);
		if (station == null) {
			return;
		}
		lock(station);
		if (player != null) {
			player.hideStorage();
		}
		notifyStation(station, player, Messages.REPAIRING);
		cancelRepair(storageId);
		Timer timer = new Timer(1f, REPAIR_SECONDS, 0, () -> enqueue(() -> onRepairDue(storageId)));
		repairTimers.put(storageId, timer);
		timer.start();
	}

	private void onRepairDue(long storageId) {
		repairTimers.remove(storageId);
		RepairStation station = stationsByStorageId.get(storageId);
		if (station == null) {
			return;
		}
		if (!verifyOrDrop(station)) {
			return;
		}
		station = stationsByStorageId.get(storageId);
		if (station == null) {
			return;
		}
		Storage storage = World.getStorage(storageId);
		Player player = actor(storageId);
		if (storage == null || storage.isTransient()) {
			drop(storageId);
			return;
		}
		Item[] items = storage.getItems();
		Item target = firstWhitelisted(items, true);
		if (target == null) {
			unlock(station);
			setState(station, RepairStation.IDLE);
			notifyStation(stationsByStorageId.get(storageId), player, Messages.READY);
			return;
		}
		// Quote while still damaged, then durability, then consume.
		List<RepairPricing.Need> recipe = RepairPricing.recipeFor(target);
		Items.ItemDefinition def = target.getDefinition();
		if (def != null && def.durability > 0) {
			target.setDurability(def.durability);
		}
		for (RepairPricing.Need need : recipe) {
			if (!need.consume()) {
				continue;
			}
			int rest = storage.removeItem(need.typeId(), need.variant(), need.amount());
			if (rest > 0) {
				System.out.println("[RepairChest] Missing " + rest + "x " + need.label()
						+ " after repair on " + station.name());
			}
		}
		unlock(station);
		setState(station, RepairStation.DONE);
		notifyStation(stationsByStorageId.get(storageId), player, Messages.COMPLETE);
	}

	private int countWhitelisted(Item[] items, boolean damagedOnly) {
		int count = 0;
		if (items == null) {
			return 0;
		}
		for (Item item : items) {
			if (item == null) {
				continue;
			}
			if (!isWhitelisted(item)) {
				continue;
			}
			if (damagedOnly && !isDamaged(item)) {
				continue;
			}
			count++;
		}
		return count;
	}

	private Item firstWhitelisted(Item[] items, boolean damagedOnly) {
		if (items == null) {
			return null;
		}
		for (Item item : items) {
			if (item == null) {
				continue;
			}
			if (!isWhitelisted(item)) {
				continue;
			}
			if (damagedOnly && !isDamaged(item)) {
				continue;
			}
			return item;
		}
		return null;
	}

	private boolean isWhitelisted(Item item) {
		String kind = itemKind(item);
		short typeId = typeId(item);
		int variant = item.getVariant();
		for (RepairRepository.WhitelistEntry entry : whitelist) {
			if (entry.matches(kind, typeId, variant)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isDamaged(Item item) {
		Items.ItemDefinition def = item.getDefinition();
		if (def == null || def.durability <= 0) {
			return false;
		}
		return item.getDurability() < def.durability;
	}

	private static List<RepairPricing.Need> missingNeeds(Item[] items, List<RepairPricing.Need> recipe) {
		var missing = new ArrayList<RepairPricing.Need>();
		for (RepairPricing.Need need : recipe) {
			int left = need.amount() - countMaterial(items, need);
			if (left > 0) {
				missing.add(new RepairPricing.Need(
						need.typeId(), need.variant(), left, need.label(), need.consume()));
			}
		}
		return missing;
	}

	private static int countMaterial(Item[] items, RepairPricing.Need recipe) {
		int have = 0;
		if (items == null) {
			return 0;
		}
		for (Item item : items) {
			if (item == null) {
				continue;
			}
			if (!recipe.consume()) {
				if (RepairPricing.isKnife(item)) {
					have += Math.max(item.getStack(), 1);
				}
				continue;
			}
			if (itemKind(item).equals(KIND_ITEM)
					&& item.getTypeID() == recipe.typeId()
					&& item.getVariant() == recipe.variant()) {
				have += item.getStack();
			}
		}
		return have;
	}

	private static String itemKind(Item item) {
		if (item instanceof Item.ObjectItem) {
			return KIND_OBJECT;
		}
		if (item instanceof Item.ConstructionItem) {
			return KIND_CONSTRUCTION;
		}
		if (item instanceof Item.ClothingItem) {
			return KIND_CLOTHING;
		}
		return KIND_ITEM;
	}

	private static short typeId(Item item) {
		if (item instanceof Item.ObjectItem objectItem) {
			return objectItem.getObjectID();
		}
		if (item instanceof Item.ConstructionItem constructionItem) {
			return constructionItem.getConstructionID();
		}
		if (item instanceof Item.ClothingItem clothingItem) {
			return clothingItem.getClothingID();
		}
		return item.getTypeID();
	}

	private void lock(RepairStation station) {
		ObjectElement object = findObject(station);
		if (object != null) {
			object.setInfo(LOCK_INFO);
		}
	}

	private void unlock(RepairStation station) {
		ObjectElement object = findObject(station);
		if (object != null) {
			object.setInfo(UNLOCK_INFO);
		}
	}

	private void updateSign(RepairStation station, String text) {
		updateSign(station, text, TextAnchor.MiddleCenter);
	}

	private void updateSign(RepairStation station, String text, TextAnchor anchor) {
		if (station == null || !station.hasSign()) {
			return;
		}
		if (!verifySignOrUnlink(station)) {
			return;
		}
		Sign sign = World.getSign(station.signId());
		if (sign != null && sign.isValid()) {
			sign.setTextAnchor(anchor);
			sign.setText(text);
		}
	}

	private void notifyStation(RepairStation station, Player player, String text) {
		notifyStation(station, player, text, TextAnchor.MiddleCenter);
	}

	private void notifyStation(RepairStation station, Player player, String text, TextAnchor anchor) {
		updateSign(station, text, anchor);
		if (player != null) {
			player.sendTextMessage(text);
		}
	}

	private void setState(RepairStation station, String state) {
		repository.setState(station.storageId(), state);
		RepairStation current = stationsByStorageId.get(station.storageId());
		if (current != null) {
			putStation(current.withState(state));
		}
	}

	private void putStation(RepairStation station) {
		RepairStation previous = stationsByStorageId.put(station.storageId(), station);
		if (previous != null) {
			unindexSign(previous);
		}
		indexSign(station);
	}

	private void indexSign(RepairStation station) {
		if (station.signId() != null) {
			signIdToStorageId.put(station.signId(), station.storageId());
		}
		if (station.signObjectId() != null) {
			signIdToStorageId.put(station.signObjectId(), station.storageId());
		}
	}

	private void unindexSign(RepairStation station) {
		if (station.signId() != null) {
			signIdToStorageId.remove(station.signId());
		}
		if (station.signObjectId() != null) {
			signIdToStorageId.remove(station.signObjectId());
		}
	}

	private boolean verifyOrDrop(RepairStation station) {
		if (matchesChest(station, World.getStorage(station.storageId()), findObject(station))) {
			return true;
		}
		drop(station.storageId());
		return false;
	}

	/** Invalid sign -> unlink + log, chest stays. */
	private boolean verifySignOrUnlink(RepairStation station) {
		if (!station.hasSign()) {
			return false;
		}
		if (matchesSign(station)) {
			return true;
		}
		System.out.println("[RepairChest] Sign missing or moved for station " + station.name()
				+ ", unlinking sign");
		clearSignText(station);
		unindexSign(station);
		repository.clearSign(station.storageId());
		RepairStation current = stationsByStorageId.get(station.storageId());
		if (current != null) {
			stationsByStorageId.put(station.storageId(), current.withoutSign());
		}
		return false;
	}

	private void drop(long storageId) {
		RepairStation station = stationsByStorageId.get(storageId);
		if (station != null) {
			unlock(station);
			clearSignText(station);
			unindexSign(station);
		}
		cancelTimers(storageId);
		repository.delete(storageId);
		stationsByStorageId.remove(storageId);
		lastActorUid.remove(storageId);
	}

	/** Blank linked sign text; world object stays. Used on remove/orphan drop. */
	private void clearSignText(RepairStation station) {
		if (station == null || !station.hasSign()) {
			return;
		}
		Sign sign = World.getSign(station.signId());
		if (sign != null && sign.isValid()) {
			sign.setText("");
		}
	}

	private static boolean matchesChest(RepairStation saved, Storage storage, ObjectElement object) {
		if (storage == null || storage.isTransient()) {
			return false;
		}
		if (storage.getCreationDate() != saved.creationDate()) {
			return false;
		}
		if (object != null) {
			if (!saved.objectType().equals(objectType(object))) {
				return false;
			}
			Vector3f pos = object.getWorldPosition();
			if (pos == null) {
				return false;
			}
			float max = MAX_IDENTITY_DISTANCE * MAX_IDENTITY_DISTANCE;
			if (pos.distanceSquared(saved.worldX(), saved.worldY(), saved.worldZ()) > max) {
				return false;
			}
		}
		return true;
	}

	private boolean matchesSign(RepairStation saved) {
		Sign sign = World.getSign(saved.signId());
		if (sign == null || !sign.isValid()) {
			return false;
		}
		ObjectElement object = sign.getRelatedObject();
		if (object == null && saved.signObjectId() != null && saved.signChunkX() != null) {
			object = World.getObject(
					saved.signObjectId(),
					saved.signChunkX(),
					saved.signChunkY(),
					saved.signChunkZ());
		}
		if (object != null) {
			if (saved.signObjectType() != null && !saved.signObjectType().equals(objectType(object))) {
				return false;
			}
			if (saved.signWorldX() != null) {
				Vector3f pos = object.getWorldPosition();
				if (pos == null) {
					return false;
				}
				float max = MAX_IDENTITY_DISTANCE * MAX_IDENTITY_DISTANCE;
				if (pos.distanceSquared(saved.signWorldX(), saved.signWorldY(), saved.signWorldZ()) > max) {
					return false;
				}
			}
			if (saved.signCreationDate() != null && object.getCreationDate() != saved.signCreationDate()) {
				return false;
			}
		}
		return true;
	}

	private static ObjectElement findObject(RepairStation station) {
		return World.getObject(station.objectId(), station.chunkX(), station.chunkY(), station.chunkZ());
	}

	private static String objectType(ObjectElement object) {
		var def = object.getDefinition();
		if (def != null && def.name != null) {
			return def.name;
		}
		return Short.toString(object.getTypeID());
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

	private static short resolveItemId(String name, short fallback) {
		Items.ItemDefinition def = Definitions.getItemDefinition(name);
		if (def != null) {
			return def.id;
		}
		return fallback;
	}

	private void rememberActor(long storageId, Player player) {
		if (player != null && player.getUID() != null) {
			lastActorUid.put(storageId, player.getUID());
		}
	}

	private Player actor(long storageId) {
		String uid = lastActorUid.get(storageId);
		if (uid == null) {
			return null;
		}
		return Server.getPlayerByUID(uid);
	}

	private void loadStations() {
		for (RepairStation station : repository.findAll()) {
			putStation(station);
		}
	}

	/**
	 * Verify every row, drop orphans, unlink bad signs, force idle + unlock.
	 * Do not scan contents and do not resume repairing.
	 */
	private void sweepAndResetIdle() {
		for (RepairStation station : List.copyOf(stationsByStorageId.values())) {
			if (!verifyOrDrop(station)) {
				continue;
			}
			RepairStation current = stationsByStorageId.get(station.storageId());
			if (current == null) {
				continue;
			}
			if (current.hasSign()) {
				verifySignOrUnlink(current);
				current = stationsByStorageId.get(station.storageId());
				if (current == null) {
					continue;
				}
			}
			unlock(current);
			setState(current, RepairStation.IDLE);
			current = stationsByStorageId.get(station.storageId());
			if (current != null && current.hasSign()) {
				updateSign(current, Messages.READY);
			}
		}
	}

	private void cancelDebounce(long storageId) {
		Timer timer = debounceTimers.remove(storageId);
		if (timer != null && !timer.isKilled()) {
			timer.kill();
		}
	}

	private void cancelRepair(long storageId) {
		Timer timer = repairTimers.remove(storageId);
		if (timer != null && !timer.isKilled()) {
			timer.kill();
		}
	}

	private void cancelTimers(long storageId) {
		cancelDebounce(storageId);
		cancelRepair(storageId);
	}

	private void cancelAllTimers() {
		for (Timer timer : debounceTimers.values()) {
			if (!timer.isKilled()) {
				timer.kill();
			}
		}
		debounceTimers.clear();
		for (Timer timer : repairTimers.values()) {
			if (!timer.isKilled()) {
				timer.kill();
			}
		}
		repairTimers.clear();
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
