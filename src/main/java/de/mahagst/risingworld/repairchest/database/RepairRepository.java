package de.mahagst.risingworld.repairchest.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import de.mahagst.risingworld.repairchest.model.RepairStation;
import de.mahagst.risingworld.repairchest.model.WhitelistEntry;

import net.risingworld.api.database.Database;

/** SQLite persistence for repair stations and the repairable-item whitelist. */
public final class RepairRepository {
	private final Database database;

	public RepairRepository(Database database) {
		this.database = database;
	}

	public void createSchema() {
		database.execute("PRAGMA foreign_keys = ON");
		// DELETE so a copied repair.db alone is complete; write load is tiny.
		database.execute("PRAGMA journal_mode=DELETE");
		database.execute("""
				CREATE TABLE IF NOT EXISTS repair_stations (
				  name TEXT PRIMARY KEY NOT NULL,
				  storage_id INTEGER UNIQUE NOT NULL,
				  object_id INTEGER NOT NULL,
				  chunk_x INTEGER NOT NULL,
				  chunk_y INTEGER NOT NULL,
				  chunk_z INTEGER NOT NULL,
				  world_x REAL NOT NULL,
				  world_y REAL NOT NULL,
				  world_z REAL NOT NULL,
				  object_type TEXT NOT NULL,
				  creation_date INTEGER NOT NULL,
				  sign_id INTEGER UNIQUE,
				  sign_object_id INTEGER,
				  sign_chunk_x INTEGER,
				  sign_chunk_y INTEGER,
				  sign_chunk_z INTEGER,
				  sign_world_x REAL,
				  sign_world_y REAL,
				  sign_world_z REAL,
				  sign_object_type TEXT,
				  sign_creation_date INTEGER,
				  state TEXT NOT NULL,
				  created_at INTEGER NOT NULL
				)
				""");
		database.execute("""
				CREATE TABLE IF NOT EXISTS repair_whitelist (
				  item_kind TEXT NOT NULL,
				  type_id INTEGER NOT NULL,
				  variant INTEGER,
				  label TEXT,
				  PRIMARY KEY (item_kind, type_id)
				)
				""");
	}

	/** Seed a repairable item. NULL variant = any. INSERT OR IGNORE keeps operator edits. */
	public void seedWhitelistItem(short typeId, String label) {
		var sql = """
				INSERT OR IGNORE INTO repair_whitelist (item_kind, type_id, variant, label)
				VALUES ('item', ?, NULL, ?)
				""";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			prep.setInt(1, typeId);
			prep.setString(2, label);
			prep.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	public Optional<RepairStation> findByName(String name) {
		return queryOne("SELECT * FROM repair_stations WHERE name = ?", prep -> prep.setString(1, name));
	}

	public List<RepairStation> findAll() {
		var stations = new ArrayList<RepairStation>();
		var sql = "SELECT * FROM repair_stations";
		try (var prep = database.getConnection().prepareStatement(sql);
				var result = prep.executeQuery()) {
			while (result.next()) {
				stations.add(readStation(result));
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return stations;
	}

	public List<WhitelistEntry> findWhitelist() {
		var entries = new ArrayList<WhitelistEntry>();
		var sql = "SELECT item_kind, type_id, variant, label FROM repair_whitelist";
		try (var prep = database.getConnection().prepareStatement(sql);
				var result = prep.executeQuery()) {
			while (result.next()) {
				int variant = result.getInt("variant");
				Integer variantOrAny = result.wasNull() ? null : variant;
				entries.add(new WhitelistEntry(
						result.getString("item_kind"),
						(short) result.getInt("type_id"),
						variantOrAny,
						result.getString("label")));
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return entries;
	}

	public boolean insert(RepairStation station) {
		var sql = """
				INSERT INTO repair_stations (
				  name, storage_id, object_id, chunk_x, chunk_y, chunk_z,
				  world_x, world_y, world_z, object_type, creation_date,
				  sign_id, sign_object_id, sign_chunk_x, sign_chunk_y, sign_chunk_z,
				  sign_world_x, sign_world_y, sign_world_z, sign_object_type, sign_creation_date,
				  state, created_at
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			bindStation(prep, station);
			prep.executeUpdate();
			return true;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	public void setState(long storageId, String state) {
		var sql = "UPDATE repair_stations SET state = ? WHERE storage_id = ?";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			prep.setString(1, state);
			prep.setLong(2, storageId);
			prep.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	public void linkSign(RepairStation station) {
		var sql = """
				UPDATE repair_stations SET
				  sign_id = ?, sign_object_id = ?,
				  sign_chunk_x = ?, sign_chunk_y = ?, sign_chunk_z = ?,
				  sign_world_x = ?, sign_world_y = ?, sign_world_z = ?,
				  sign_object_type = ?, sign_creation_date = ?
				WHERE storage_id = ?
				""";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			prep.setLong(1, station.signId());
			prep.setLong(2, station.signObjectId());
			prep.setInt(3, station.signChunkX());
			prep.setInt(4, station.signChunkY());
			prep.setInt(5, station.signChunkZ());
			prep.setFloat(6, station.signWorldX());
			prep.setFloat(7, station.signWorldY());
			prep.setFloat(8, station.signWorldZ());
			prep.setString(9, station.signObjectType());
			prep.setLong(10, station.signCreationDate());
			prep.setLong(11, station.storageId());
			prep.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	public void clearSign(long storageId) {
		var sql = """
				UPDATE repair_stations SET
				  sign_id = NULL, sign_object_id = NULL,
				  sign_chunk_x = NULL, sign_chunk_y = NULL, sign_chunk_z = NULL,
				  sign_world_x = NULL, sign_world_y = NULL, sign_world_z = NULL,
				  sign_object_type = NULL, sign_creation_date = NULL
				WHERE storage_id = ?
				""";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			prep.setLong(1, storageId);
			prep.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	public void delete(long storageId) {
		var sql = "DELETE FROM repair_stations WHERE storage_id = ?";
		try (var prep = database.getConnection().prepareStatement(sql)) {
			prep.setLong(1, storageId);
			prep.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	private Optional<RepairStation> queryOne(String sql, SqlBind bind) {
		try (var prep = database.getConnection().prepareStatement(sql)) {
			bind.apply(prep);
			try (var result = prep.executeQuery()) {
				if (result.next()) {
					return Optional.of(readStation(result));
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return Optional.empty();
	}

	private static void bindStation(PreparedStatement prep, RepairStation station) throws SQLException {
		prep.setString(1, station.name());
		prep.setLong(2, station.storageId());
		prep.setLong(3, station.objectId());
		prep.setInt(4, station.chunkX());
		prep.setInt(5, station.chunkY());
		prep.setInt(6, station.chunkZ());
		prep.setFloat(7, station.worldX());
		prep.setFloat(8, station.worldY());
		prep.setFloat(9, station.worldZ());
		prep.setString(10, station.objectType());
		prep.setLong(11, station.creationDate());
		setNullableLong(prep, 12, station.signId());
		setNullableLong(prep, 13, station.signObjectId());
		setNullableInt(prep, 14, station.signChunkX());
		setNullableInt(prep, 15, station.signChunkY());
		setNullableInt(prep, 16, station.signChunkZ());
		setNullableFloat(prep, 17, station.signWorldX());
		setNullableFloat(prep, 18, station.signWorldY());
		setNullableFloat(prep, 19, station.signWorldZ());
		if (station.signObjectType() == null) {
			prep.setNull(20, Types.VARCHAR);
		} else {
			prep.setString(20, station.signObjectType());
		}
		setNullableLong(prep, 21, station.signCreationDate());
		prep.setString(22, station.state());
		prep.setLong(23, station.createdAt());
	}

	private static RepairStation readStation(ResultSet result) throws SQLException {
		return new RepairStation(
				result.getString("name"),
				result.getLong("storage_id"),
				result.getLong("object_id"),
				result.getInt("chunk_x"),
				result.getInt("chunk_y"),
				result.getInt("chunk_z"),
				result.getFloat("world_x"),
				result.getFloat("world_y"),
				result.getFloat("world_z"),
				result.getString("object_type"),
				result.getLong("creation_date"),
				getNullableLong(result, "sign_id"),
				getNullableLong(result, "sign_object_id"),
				getNullableInt(result, "sign_chunk_x"),
				getNullableInt(result, "sign_chunk_y"),
				getNullableInt(result, "sign_chunk_z"),
				getNullableFloat(result, "sign_world_x"),
				getNullableFloat(result, "sign_world_y"),
				getNullableFloat(result, "sign_world_z"),
				result.getString("sign_object_type"),
				getNullableLong(result, "sign_creation_date"),
				result.getString("state"),
				result.getLong("created_at"));
	}

	private static void setNullableLong(PreparedStatement prep, int index, Long value) throws SQLException {
		if (value == null) {
			prep.setNull(index, Types.INTEGER);
		} else {
			prep.setLong(index, value);
		}
	}

	private static void setNullableInt(PreparedStatement prep, int index, Integer value) throws SQLException {
		if (value == null) {
			prep.setNull(index, Types.INTEGER);
		} else {
			prep.setInt(index, value);
		}
	}

	private static void setNullableFloat(PreparedStatement prep, int index, Float value) throws SQLException {
		if (value == null) {
			prep.setNull(index, Types.REAL);
		} else {
			prep.setFloat(index, value);
		}
	}

	private static Long getNullableLong(ResultSet result, String column) throws SQLException {
		long value = result.getLong(column);
		return result.wasNull() ? null : value;
	}

	private static Integer getNullableInt(ResultSet result, String column) throws SQLException {
		int value = result.getInt(column);
		return result.wasNull() ? null : value;
	}

	private static Float getNullableFloat(ResultSet result, String column) throws SQLException {
		float value = result.getFloat(column);
		return result.wasNull() ? null : value;
	}

	@FunctionalInterface
	private interface SqlBind {
		void apply(PreparedStatement prep) throws SQLException;
	}

}
