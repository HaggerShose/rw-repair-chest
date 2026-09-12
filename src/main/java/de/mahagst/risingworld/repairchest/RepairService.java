package de.mahagst.risingworld.repairchest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.risingworld.api.Server;
import net.risingworld.api.Timer;
import net.risingworld.api.World;
import net.risingworld.api.definitions.Definitions;
import net.risingworld.api.definitions.Items;
import net.risingworld.api.definitions.Objects;
import net.risingworld.api.objects.Item;
import net.risingworld.api.objects.Player;
import net.risingworld.api.objects.Sign;
import net.risingworld.api.objects.Storage;
import net.risingworld.api.objects.world.ObjectElement;
import net.risingworld.api.ui.style.TextAnchor;

/**
 * Admin-registered named repair stations: put a damaged whitelisted item,
 * quote materials on a linked sign, lock briefly, restore durability, consume mats.
 */
public final class RepairService {
	public static final long LOCK_INFO = 1L;
	static final long UNLOCK_INFO = 0L;
	static final String KIND_ITEM = "item";
	static final String KIND_OBJECT = "object";
	static final String KIND_CONSTRUCTION = "construction";
	static final String KIND_CLOTHING = "clothing";

	private final RepairRepository repository;
	private final RepairSettingsStore store;
	private volatile RepairSettings settings;
	private final Consumer<Runnable> enqueue;
	private final StationRegistry stations;
	private final Map<Long, Timer> debounceTimers = new HashMap<>();
	private final Map<Long, Timer> repairTimers = new HashMap<>();
	private final Map<Long, String> lastActorUid = new HashMap<>();
	private List<WhitelistEntry> whitelist = List.of();

	public RepairService(RepairRepository repository, RepairSettings settings, RepairSettingsStore store,
			Consumer<Runnable> enqueue) {
		this.repository = repository;
		this.settings = settings;
		this.store = store;
		this.enqueue = enqueue;
		this.stations = new StationRegistry(repository, settings);
	}

	/** Load stations, validate identities and reset to idle before listeners start. */
	public void start() {
		syncWhitelistFromSettings();
		stations.loadAll();
		stations.sweepAndResetIdle();
	}

	public RepairSettings settings() {
		return settings;
	}

	/** Swap operator knobs and resync whitelist. Stations and pending timers stay. */
	public void applySettings(RepairSettings next) {
		this.settings = next;
		stations.replaceSettings(next);
		syncWhitelistFromSettings();
	}

	public boolean isAllowed(Player player) {
		if (player.isAdmin()) {
			return true;
		}
		String uid = player.getUID();
		return uid != null && settings.allowedUids().contains(uid);
	}

	/** null = saved; otherwise an error for the caller to show. */
	public String addRepairable(String name, short fallbackTypeId) {
		if (name == null || name.isBlank()) {
			return "Missing item name.";
		}
		RepairSettings next = settings.addingRepairable(name, fallbackTypeId);
		if (next == settings) {
			return "Already listed: " + name.trim();
		}
		return persistWhitelist(next);
	}

	/** null = saved; otherwise an error for the caller to show. */
	public String removeRepairable(String name) {
		if (name == null || name.isBlank()) {
			return "Missing item name.";
		}
		RepairSettings next = settings.removingRepairable(name);
		if (next == settings) {
			return "Not listed: " + name.trim();
		}
		return persistWhitelist(next);
	}

	private String persistWhitelist(RepairSettings next) {
		try {
			store.save(next);
		} catch (IOException e) {
			System.out.println("[RepairChest] Could not save settings.json: " + e.getMessage());
			return "Could not save settings.json";
		}
		applySettings(next);
		return null;
	}

	private void syncWhitelistFromSettings() {
		var entries = new ArrayList<WhitelistEntry>();
		for (var seed : settings.whitelistSeeds()) {
			short typeId = resolveItemId(seed.name(), seed.fallbackTypeId());
			entries.add(new WhitelistEntry(KIND_ITEM, typeId, null, seed.name()));
		}
		repository.replaceWhitelist(entries);
		whitelist = repository.findWhitelist();
	}

	public void stop() {
		cancelAllTimers();
	}

	public boolean isRepairing(long storageId) {
		RepairStation station = stations.get(storageId);
		return station != null && station.isRepairing();
	}

	public boolean isStationSign(long signId) {
		return stations.containsSign(signId);
	}

	public boolean isIdleSign(long signId) {
		Long storageId = stations.storageIdForSign(signId);
		if (storageId == null) {
			return false;
		}
		RepairStation station = stations.get(storageId);
		return station != null && station.isIdle();
	}

	public void registerChest(Player player, ObjectElement object, Storage storage, String name) {
		var def = object.getDefinition();
		if (def == null || def.type != Objects.Type.Storage) {
			player.sendTextMessage(Messages.NOT_STORAGE_CHEST);
			return;
		}
		String typeName = StationRegistry.objectType(object);
		if (!settings.allowedChestTypes().contains(typeName)) {
			player.sendTextMessage(Messages.chestTypeNotAllowed(typeName, object.getTypeID()));
			return;
		}
		if (stations.containsStorage(storage.getID())) {
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
				StationRegistry.objectType(object),
				storage.getCreationDate(),
				null, null, null, null, null,
				null, null, null, null, null,
				RepairStation.IDLE,
				System.currentTimeMillis());
		if (!repository.insert(station)) {
			player.sendTextMessage(Messages.SAVE_FAILED);
			return;
		}
		stations.put(station);
		player.sendTextMessage(Messages.chestCreated(name));
	}

	public void registerSign(Player player, ObjectElement object, Sign sign, String name) {
		var existing = repository.findByName(name);
		if (existing.isEmpty()) {
			player.sendTextMessage(Messages.NO_CHEST_WITH_NAME);
			return;
		}
		RepairStation station = existing.get();
		if (!stations.verifyOrDrop(station)) {
			player.sendTextMessage(Messages.CHEST_INVALID);
			return;
		}
		station = stations.get(station.storageId());
		if (station == null) {
			player.sendTextMessage(Messages.CHEST_INVALID);
			return;
		}
		if (station.hasSign()) {
			player.sendTextMessage(Messages.STATION_HAS_SIGN);
			return;
		}
		long signId = sign.getID();
		if (stations.containsSign(signId)) {
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
				StationRegistry.objectType(object),
				object.getCreationDate());
		repository.linkSign(linked);
		stations.put(linked);
		stations.updateSign(linked, Messages.READY);
		player.sendTextMessage(Messages.signLinked(name));
	}

	public void remove(Player player, ObjectElement object, Storage storage) {
		RepairStation station = stations.get(storage.getID());
		if (station == null) {
			player.sendTextMessage(Messages.CHEST_NOT_REGISTERED);
			return;
		}
		drop(storage.getID());
		player.sendTextMessage(Messages.CHEST_REMOVED);
	}

	public void info(Player player, ObjectElement object, Storage storage) {
		RepairStation station = requireValid(player, storage);
		if (station == null) {
			return;
		}
		boolean pending = debounceTimers.containsKey(station.storageId());
		StringBuilder white = new StringBuilder();
		for (WhitelistEntry entry : whitelist) {
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
		RepairStation station = stations.get(storage.getID());
		if (station == null) {
			player.sendTextMessage(Messages.CHEST_NOT_REGISTERED);
			return null;
		}
		if (!stations.verifyOrDrop(station)) {
			player.sendTextMessage(Messages.CHEST_INVALID);
			return null;
		}
		return stations.get(storage.getID());
	}

	public void onPut(Storage storage, Player player) {
		if (storage == null) {
			return;
		}
		RepairStation station = stations.get(storage.getID());
		if (station == null || station.isRepairing()) {
			return;
		}
		rememberActor(storage.getID(), player);
		restartDebounce(storage.getID());
	}

	public void onTake(Storage storage, Player player, Item taken) {
		if (storage == null) {
			return;
		}
		RepairStation station = stations.get(storage.getID());
		if (station == null || station.isRepairing()) {
			return;
		}
		rememberActor(storage.getID(), player);
		// After repair: taking the repaired whitelist item ends the cycle.
		// Do not scan storage immediately -- the item is often still listed in the event frame.
		if (station.isDone()) {
			cancelDebounce(storage.getID());
			if (!stations.verifyOrDrop(station)) {
				return;
			}
			station = stations.get(storage.getID());
			if (station == null) {
				return;
			}
			if (taken != null && isWhitelisted(taken)) {
				stations.setState(station, RepairStation.IDLE);
				notifyStation(stations.get(storage.getID()), player, Messages.READY);
				return;
			}
			restartDebounce(storage.getID(), settings.postTakeScanSeconds());
			return;
		}
		restartDebounce(storage.getID(), settings.debounceSeconds());
	}

	private void restartDebounce(long storageId) {
		restartDebounce(storageId, settings.debounceSeconds());
	}

	private void restartDebounce(long storageId, float delaySeconds) {
		cancelDebounce(storageId);
		float delay = Math.max(delaySeconds, 0.1f);
		Timer timer = new Timer(1f, delay, 0, () -> enqueue.accept(() -> onDebounceDue(storageId)));
		debounceTimers.put(storageId, timer);
		timer.start();
	}

	private void onDebounceDue(long storageId) {
		debounceTimers.remove(storageId);
		RepairStation station = stations.get(storageId);
		if (station == null) {
			return;
		}
		if (station.isRepairing()) {
			return;
		}
		if (!stations.verifyOrDrop(station)) {
			return;
		}
		station = stations.get(storageId);
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
				stations.setState(station, RepairStation.IDLE);
				notifyStation(stations.get(station.storageId()), player, Messages.READY);
			}
			return;
		}
		boolean wasIdle = station.isIdle();
		int any = countWhitelisted(items, false);
		int damaged = countWhitelisted(items, true);
		// Too many repairables always needs feedback, even from idle.
		if (any > 1) {
			stations.setState(station, RepairStation.QUOTED);
			notifyStation(stations.get(station.storageId()), player, Messages.ONE_AT_A_TIME);
			return;
		}
		// Idle stays passive unless exactly one damaged whitelist item is present.
		if (wasIdle && !(any == 1 && damaged == 1)) {
			return;
		}
		if (any == 0) {
			stations.setState(station, RepairStation.IDLE);
			notifyStation(stations.get(station.storageId()), player, Messages.READY);
			return;
		}
		// Exactly one whitelisted item.
		if (damaged == 0) {
			stations.setState(station, RepairStation.QUOTED);
			notifyStation(stations.get(station.storageId()), player, Messages.ALREADY_FULL);
			return;
		}
		Item target = firstWhitelisted(items, true);
		if (target == null) {
			stations.setState(station, RepairStation.IDLE);
			notifyStation(stations.get(station.storageId()), player, Messages.READY);
			return;
		}
		var recipe = RepairPricing.recipeFor(target, settings);
		stations.setState(station, RepairStation.QUOTED);
		station = stations.get(station.storageId());
		if (recipe.isEmpty()) {
			Items.ItemDefinition def = target.getDefinition();
			System.out.println("[RepairChest] No usable crafting recipe for "
					+ (def != null ? def.name : "?") + " variant " + target.getVariant()
					+ " (type " + target.getTypeID() + ")");
			notifyStation(station, player, Messages.NO_RECIPE);
			return;
		}
		List<RepairPricing.Need> missing = RepairPricing.plan(items, target, recipe.get()).missing();
		if (!missing.isEmpty()) {
			notifyStation(station, player, Messages.needs(missing), TextAnchor.UpperLeft);
			return;
		}
		beginRepair(station, player);
	}

	private void beginRepair(RepairStation station, Player player) {
		long storageId = station.storageId();
		cancelDebounce(storageId);
		stations.setState(station, RepairStation.REPAIRING);
		station = stations.get(storageId);
		if (station == null) {
			return;
		}
		stations.lock(station);
		if (player != null) {
			player.hideStorage();
		}
		notifyStation(station, player, Messages.REPAIRING);
		cancelRepair(storageId);
		Timer timer = new Timer(1f, settings.repairSeconds(), 0, () -> enqueue.accept(() -> onRepairDue(storageId)));
		repairTimers.put(storageId, timer);
		timer.start();
	}

	private void onRepairDue(long storageId) {
		repairTimers.remove(storageId);
		RepairStation station = stations.get(storageId);
		if (station == null) {
			return;
		}
		if (!stations.verifyOrDrop(station)) {
			return;
		}
		station = stations.get(storageId);
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
			stations.unlock(station);
			stations.setState(station, RepairStation.IDLE);
			notifyStation(stations.get(storageId), player, Messages.READY);
			return;
		}
		// Quote while still damaged, then durability, then consume.
		var recipe = RepairPricing.recipeFor(target, settings);
		if (recipe.isEmpty()) {
			stations.unlock(station);
			stations.setState(station, RepairStation.QUOTED);
			notifyStation(stations.get(storageId), player, Messages.NO_RECIPE);
			return;
		}
		RepairPricing.Plan materials = RepairPricing.plan(items, target, recipe.get());
		if (!materials.missing().isEmpty()) {
			stations.unlock(station);
			stations.setState(station, RepairStation.QUOTED);
			notifyStation(stations.get(storageId), player, Messages.needs(materials.missing()),
					TextAnchor.UpperLeft);
			return;
		}
		Items.ItemDefinition def = target.getDefinition();
		if (def != null && def.durability > 0) {
			repairAndConsume(target, def.durability, storage, materials);
		}
		stations.unlock(station);
		stations.setState(station, RepairStation.DONE);
		notifyStation(stations.get(storageId), player, Messages.COMPLETE);
	}

	/** Restore durability first so a failed material removal cannot leave a broken tool. */
	static void repairAndConsume(Item target, int maxDurability, Storage storage, RepairPricing.Plan materials) {
		if (!materials.missing().isEmpty()) {
			throw new IllegalArgumentException("Cannot repair without all required materials");
		}
		target.setDurability(maxDurability);
		for (RepairPricing.Removal removal : materials.removals()) {
			storage.removeItem(removal.slot(), removal.amount());
		}
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
		for (WhitelistEntry entry : whitelist) {
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

	private void notifyStation(RepairStation station, Player player, String text) {
		notifyStation(station, player, text, TextAnchor.MiddleCenter);
	}

	private void notifyStation(RepairStation station, Player player, String text, TextAnchor anchor) {
		stations.updateSign(station, text, anchor);
		if (player != null && (station == null || !station.hasSign())) {
			player.sendTextMessage(text);
		}
	}

	private void drop(long storageId) {
		cancelTimers(storageId);
		lastActorUid.remove(storageId);
		stations.drop(storageId);
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
}
