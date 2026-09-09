package de.mahagst.risingworld.repairchest;

/** One registered repair station (chest + optional sign). */
record RepairStation(
		String name,
		long storageId,
		long objectId,
		int chunkX,
		int chunkY,
		int chunkZ,
		float worldX,
		float worldY,
		float worldZ,
		String objectType,
		long creationDate,
		Long signId,
		Long signObjectId,
		Integer signChunkX,
		Integer signChunkY,
		Integer signChunkZ,
		Float signWorldX,
		Float signWorldY,
		Float signWorldZ,
		String signObjectType,
		Long signCreationDate,
		String state,
		long createdAt) {

	static final String IDLE = "idle";
	static final String QUOTED = "quoted";
	static final String REPAIRING = "repairing";
	static final String DONE = "done";

	boolean isIdle() {
		return IDLE.equals(state);
	}

	boolean isRepairing() {
		return REPAIRING.equals(state);
	}

	boolean isDone() {
		return DONE.equals(state);
	}

	boolean hasSign() {
		return signId != null;
	}

	RepairStation withState(String newState) {
		return new RepairStation(
				name, storageId, objectId, chunkX, chunkY, chunkZ,
				worldX, worldY, worldZ, objectType, creationDate,
				signId, signObjectId, signChunkX, signChunkY, signChunkZ,
				signWorldX, signWorldY, signWorldZ, signObjectType, signCreationDate,
				newState, createdAt);
	}

	RepairStation withSign(
			long newSignId,
			long newSignObjectId,
			int newSignChunkX,
			int newSignChunkY,
			int newSignChunkZ,
			float newSignWorldX,
			float newSignWorldY,
			float newSignWorldZ,
			String newSignObjectType,
			long newSignCreationDate) {
		return new RepairStation(
				name, storageId, objectId, chunkX, chunkY, chunkZ,
				worldX, worldY, worldZ, objectType, creationDate,
				newSignId, newSignObjectId, newSignChunkX, newSignChunkY, newSignChunkZ,
				newSignWorldX, newSignWorldY, newSignWorldZ, newSignObjectType, newSignCreationDate,
				state, createdAt);
	}

	RepairStation withoutSign() {
		return new RepairStation(
				name, storageId, objectId, chunkX, chunkY, chunkZ,
				worldX, worldY, worldZ, objectType, creationDate,
				null, null, null, null, null,
				null, null, null, null, null,
				state, createdAt);
	}
}
