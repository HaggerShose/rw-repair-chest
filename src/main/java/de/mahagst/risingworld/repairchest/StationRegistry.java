package de.mahagst.risingworld.repairchest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.risingworld.api.World;
import net.risingworld.api.objects.Sign;
import net.risingworld.api.objects.Storage;
import net.risingworld.api.objects.world.ObjectElement;
import net.risingworld.api.ui.style.TextAnchor;
import net.risingworld.api.utils.Vector3f;

/**
 * In-memory station index plus identity checks. SQLite remains source of truth;
 * this registry mirrors registered storage/sign ids and drops orphans.
 */
final class StationRegistry {
	private final RepairRepository repository;
	private volatile RepairSettings settings;
	private final Map<Long, RepairStation> stationsByStorageId = new HashMap<>();
	private final Map<Long, Long> signIdToStorageId = new HashMap<>();

	StationRegistry(RepairRepository repository, RepairSettings settings) {
		this.repository = repository;
		this.settings = settings;
	}

	void replaceSettings(RepairSettings settings) {
		this.settings = settings;
	}

	void loadAll() {
		for (RepairStation station : repository.findAll()) {
			put(station);
		}
	}

	/**
	 * Verify every row, drop orphans, unlink bad signs, force idle + unlock.
	 * Do not scan contents and do not resume repairing.
	 */
	void sweepAndResetIdle() {
		for (RepairStation station : List.copyOf(stationsByStorageId.values())) {
			if (!verifyOrDrop(station)) {
				continue;
			}
			RepairStation current = get(station.storageId());
			if (current == null) {
				continue;
			}
			if (current.hasSign()) {
				verifySignOrUnlink(current);
				current = get(station.storageId());
				if (current == null) {
					continue;
				}
			}
			unlock(current);
			setState(current, RepairStation.IDLE);
			current = get(station.storageId());
			if (current != null && current.hasSign()) {
				updateSign(current, Messages.READY);
			}
		}
	}

	RepairStation get(long storageId) {
		return stationsByStorageId.get(storageId);
	}

	boolean containsStorage(long storageId) {
		return stationsByStorageId.containsKey(storageId);
	}

	boolean containsSign(long signId) {
		return signIdToStorageId.containsKey(signId);
	}

	Long storageIdForSign(long signId) {
		return signIdToStorageId.get(signId);
	}

	void put(RepairStation station) {
		RepairStation previous = stationsByStorageId.put(station.storageId(), station);
		if (previous != null) {
			unindexSign(previous);
		}
		indexSign(station);
	}

	void setState(RepairStation station, String state) {
		repository.setState(station.storageId(), state);
		RepairStation current = get(station.storageId());
		if (current != null) {
			put(current.withState(state));
		}
	}

	boolean verifyOrDrop(RepairStation station) {
		if (matchesChest(station, World.getStorage(station.storageId()), findObject(station))) {
			return true;
		}
		drop(station.storageId());
		return false;
	}

	/** Invalid sign -> unlink + log, chest stays. */
	boolean verifySignOrUnlink(RepairStation station) {
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
		RepairStation current = get(station.storageId());
		if (current != null) {
			stationsByStorageId.put(station.storageId(), current.withoutSign());
		}
		return false;
	}

	/**
	 * Unlock, blank sign, unindex, delete DB row and remove from RAM.
	 * Callers must cancel station timers / actor state themselves.
	 */
	void drop(long storageId) {
		RepairStation station = get(storageId);
		if (station != null) {
			unlock(station);
			clearSignText(station);
			unindexSign(station);
		}
		repository.delete(storageId);
		stationsByStorageId.remove(storageId);
	}

	void lock(RepairStation station) {
		ObjectElement object = findObject(station);
		if (object != null) {
			object.setInfo(RepairService.LOCK_INFO);
		}
	}

	void unlock(RepairStation station) {
		ObjectElement object = findObject(station);
		if (object != null) {
			object.setInfo(RepairService.UNLOCK_INFO);
		}
	}

	void updateSign(RepairStation station, String text) {
		updateSign(station, text, TextAnchor.MiddleCenter);
	}

	void updateSign(RepairStation station, String text, TextAnchor anchor) {
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

	void clearSignText(RepairStation station) {
		if (station == null || !station.hasSign()) {
			return;
		}
		Sign sign = World.getSign(station.signId());
		if (sign != null && sign.isValid()) {
			sign.setText("");
		}
	}

	static ObjectElement findObject(RepairStation station) {
		return World.getObject(station.objectId(), station.chunkX(), station.chunkY(), station.chunkZ());
	}

	static String objectType(ObjectElement object) {
		var def = object.getDefinition();
		if (def != null && def.name != null) {
			return def.name;
		}
		return Short.toString(object.getTypeID());
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

	private boolean matchesChest(RepairStation saved, Storage storage, ObjectElement object) {
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
			float max = settings.interactDistance() * settings.interactDistance();
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
				float max = settings.interactDistance() * settings.interactDistance();
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
}
