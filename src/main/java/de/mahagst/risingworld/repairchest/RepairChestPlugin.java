package de.mahagst.risingworld.repairchest;

import net.risingworld.api.Plugin;
import net.risingworld.api.database.Database;

/** Opens SQLite, wires service + listener, owns plugin lifecycle. */
public class RepairChestPlugin extends Plugin {
	private Database database;
	private RepairService repairService;
	private RepairCommands commands;

	@Override
	public void onEnable() {
		RepairSettingsStore store = new RepairSettingsStore(getPath() + "/settings.json");
		RepairSettings settings = store.loadOrCreate();
		database = getSQLiteConnection(getPath() + "/repair.db");
		if (database == null) {
			System.out.println("[RepairChest] Failed to open SQLite database");
			return;
		}
		RepairRepository repository = new RepairRepository(database);
		repository.createSchema();
		repairService = new RepairService(repository, settings, this::enqueue);
		repairService.start();
		commands = new RepairCommands(repairService, store);
		registerEventListener(commands);
		System.out.println("[RepairChest] enabled");
	}

	@Override
	public void onDisable() {
		if (commands != null) {
			unregisterEventListener(commands);
		}
		if (repairService != null) {
			repairService.stop();
		}
		if (database != null) {
			database.execute("PRAGMA wal_checkpoint(TRUNCATE)");
			database.close();
		}
		System.out.println("[RepairChest] disabled");
	}
}
