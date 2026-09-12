package de.mahagst.risingworld.repairchest;

import net.risingworld.api.Plugin;
import net.risingworld.api.database.Database;

/** Opens SQLite, wires service + listener, owns plugin lifecycle. */
public class RepairChestPlugin extends Plugin {
	private static final String OZ_PLUGIN_NAME = "OZ - Tools";
	private static final String OZ_UI_CLASS = "de.mahagst.risingworld.repairchest.oz.OzWhitelistUi";

	private Database database;
	private RepairService repairService;
	private RepairCommands commands;
	private Class<?> ozWhitelistUi;

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
		repairService = new RepairService(repository, settings, store, this::enqueue);
		repairService.start();
		commands = new RepairCommands(repairService, store);
		registerEventListener(commands);
		tryRegisterOzUi();
		System.out.println("[RepairChest] enabled");
	}

	@Override
	public void onDisable() {
		unregisterOzUi();
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

	private void tryRegisterOzUi() {
		try {
			if (getPluginByName(OZ_PLUGIN_NAME) == null) {
				System.out.println("[RepairChest] OZ Tools not found, settings UI skipped");
				return;
			}
			Class<?> type = Class.forName(OZ_UI_CLASS);
			type.getMethod("register", String.class, String.class, RepairService.class)
					.invoke(null, getName(), getDescription("version"), repairService);
			ozWhitelistUi = type;
		} catch (ClassNotFoundException | NoClassDefFoundError e) {
			System.out.println("[RepairChest] OZ Tools API missing, settings UI skipped");
		} catch (ReflectiveOperationException e) {
			System.out.println("[RepairChest] Could not register OZ settings UI: " + e.getMessage());
		}
	}

	private void unregisterOzUi() {
		if (ozWhitelistUi == null) {
			return;
		}
		try {
			ozWhitelistUi.getMethod("unregister").invoke(null);
		} catch (NoClassDefFoundError e) {
			System.out.println("[RepairChest] OZ Tools API missing during settings UI unregister");
		} catch (ReflectiveOperationException e) {
			System.out.println("[RepairChest] Could not unregister OZ settings UI: " + e.getMessage());
		}
		ozWhitelistUi = null;
	}
}
