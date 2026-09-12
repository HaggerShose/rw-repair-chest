package de.mahagst.risingworld.repairchest;

import de.mahagst.risingworld.repairchest.command.RepairCommands;
import de.mahagst.risingworld.repairchest.config.RepairSettings;
import de.mahagst.risingworld.repairchest.database.RepairRepository;
import de.mahagst.risingworld.repairchest.listener.RepairListener;
import de.mahagst.risingworld.repairchest.repair.RepairService;

import net.risingworld.api.Plugin;
import net.risingworld.api.database.Database;

/** Creates the repair components and owns their plugin lifecycle. */
public class RepairChestPlugin extends Plugin {
	private Database database;
	private RepairService repairService;
	private RepairCommands commands;
	private RepairListener listener;

	@Override
	public void onEnable() {
		RepairSettings settings = RepairSettings.defaults();
		database = getSQLiteConnection(getPath() + "/repair.db");
		if (database == null) {
			System.out.println("[RepairChest] Failed to open SQLite database");
			return;
		}
		RepairRepository repository = new RepairRepository(database);
		repository.createSchema();
		repairService = new RepairService(repository, settings, this::enqueue);
		repairService.start();
		commands = new RepairCommands(repairService);
		listener = new RepairListener(repairService);
		registerEventListener(commands);
		registerEventListener(listener);
		System.out.println("[RepairChest] enabled");
	}

	@Override
	public void onDisable() {
		if (commands != null) {
			unregisterEventListener(commands);
		}
		if (listener != null) {
			unregisterEventListener(listener);
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
