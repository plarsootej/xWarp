package de.xzise.xwarp.dataconnections;

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;

import de.xzise.xwarp.EditorPermissions;
import de.xzise.xwarp.Permissions;

import me.taylorkelly.mywarp.MyWarp;
import me.taylorkelly.mywarp.Warp;
import me.taylorkelly.mywarp.Warp.Visibility;

public class SQLiteConnection implements DataConnection {

	public final static String DATABASE = "jdbc:sqlite:homes-warps.db";
	private final static String WARP_TABLE = "CREATE TABLE `warpTable` ("
			+ "`id` INTEGER PRIMARY KEY,"
			+ "`name` varchar(32) NOT NULL DEFAULT 'warp',"
			+ "`creator` varchar(32) NOT NULL DEFAULT 'Player',"
			+ "`world` varchar(32) NOT NULL,"
			+ "`x` DPUBLE NOT NULL DEFAULT '0',"
			+ "`y` DOUBLE NOT NULL DEFAULT '0',"
			+ "`z` DOUBLE NOT NULL DEFAULT '0',"
			+ "`yaw` smallint NOT NULL DEFAULT '0',"
			+ "`pitch` smallint NOT NULL DEFAULT '0',"
			+ "`publicLevel` smallint NOT NULL DEFAULT '1',"
			+ "`welcomeMessage` varchar(100) NOT NULL DEFAULT ''" + ");";
	private final static String PERMISSIONS_TABLE = "CREATE TABLE `permissions` ("
			+ "`id` INTEGER NOT NULL,"
			+ "`editor` varchar(32) NOT NULL,"
			+ "`value` " + ");";
	
	private final static String VERSION_TABLE = "CREATE TABLE `meta` (`name` varchar(32) NOT NULL, `value` int NOT NULL);";

	private final static int TARGET_VERSION = 2;

	private Server server;
	private Connection connection;
	
	public SQLiteConnection(Server server) {
		// Nothing to do here
		this.server = server;
	}
	
	private static IllegalArgumentException newLoadException() {
		return new IllegalArgumentException("Couldn't load database");
	}
	
	private void initFile(File file) {
		this.free();
        try {
            Class.forName("org.sqlite.JDBC");
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            this.connection.setAutoCommit(false);
        } catch (ClassNotFoundException e) {
        	MyWarp.logger.severe("Class not found", e);
        	throw newLoadException();
        } catch (SQLException e) {
            MyWarp.logger.severe("Generic SQLException", e);
        	throw newLoadException();
        }
	}
	
	public void free() {
        if (this.connection != null) {
        	MyWarp.logger.info("Close connection!");
            try {
            	this.connection.close();
            	this.connection = null;
            } catch (SQLException e) {
                e.printStackTrace();
            }
        } else {
        	this.connection = null;
        }
	}
	
	protected void finalize() throws Throwable
	{
		this.free();
		super.finalize();
	}
	
	private class WarpPermission {
		public final int id;
		public final String editor;
		
		public WarpPermission(int id, String editor) {
			this.id = id;
			this.editor = editor;
		}
	}
	
	private void update() {
		int version = getVersion();
		
		if (version < TARGET_VERSION) {
			MyWarp.logger.info("Database layout is outdated (" + version + ")! Updating to " + TARGET_VERSION + ".");
			Statement statement = null;
			PreparedStatement convertedWarp = null;
			PreparedStatement permissionsInsert = null;
			ResultSet set = null;
			try {
				statement = this.connection.createStatement();
				
				// Copy old database
				if (tableExists("warpTable")) {
					// Backup it
					statement.execute("ALTER TABLE warpTable RENAME TO warpTable_backup");
					MyWarp.logger.info("Backuping old database.");
				}
				// Create new database
				statement.executeUpdate(WARP_TABLE);
				if (tableExists("warpTable_backup")) {
					// Select line by line
					String world = server.getWorlds().get(0).getName();
					set = statement.executeQuery("SELECT * FROM warpTable_backup");
					List<WarpPermission> list = new ArrayList<WarpPermission>();
					convertedWarp = this.connection.prepareStatement("INSERT INTO warpTable (id, name, creator, world, x, y, z, yaw, pitch, publicLevel, welcomeMessage) VALUES (?,?,?,?,?,?,?,?,?,?,?)");
					while (set.next()) {
						int id = set.getInt("id");
						convertedWarp.setInt(1, id);
						convertedWarp.setString(2, set.getString("name"));
						convertedWarp.setString(3, set.getString("creator"));
						if (version < 1) {
							convertedWarp.setString(4, world);
						} else {
							convertedWarp.setString(4, set.getString("world"));
						}
						convertedWarp.setDouble(5, set.getDouble("x"));
						convertedWarp.setInt(6, set.getInt("y"));
						convertedWarp.setDouble(7, set.getDouble("z"));
						convertedWarp.setInt(8, set.getInt("yaw"));
						convertedWarp.setInt(9, set.getInt("pitch"));
						if (version < 0) {
							if (set.getBoolean("publicAll")) {
								convertedWarp.setInt(10, 1);
							} else {
								convertedWarp.setInt(10, 0);
							}
						} else {
							convertedWarp.setInt(10, set.getInt("publicLevel"));
						}
						if (version < 2) {
							List<String> p = processList(set.getString("permissions"));
							for (String string : p) {
								list.add(new WarpPermission(id, string));
							}
						}
						convertedWarp.setString(11, set.getString("welcomeMessage"));
						convertedWarp.executeUpdate();						
					}
					
					if (version < 2) {
						statement.executeUpdate(PERMISSIONS_TABLE);
						MyWarp.logger.info("Adding permissions table");
						
						if (list.size() > 0) {
							permissionsInsert = this.connection.prepareStatement("INSERT OR IGNORE INTO permissions (id, editor, value) VALUES (?,?,?)");
							
							for (WarpPermission warpPermission : list) {
								permissionsInsert.setInt(1, warpPermission.id);
								permissionsInsert.setString(2, warpPermission.editor);
								permissionsInsert.setInt(3, Permissions.WARP.id);
								permissionsInsert.addBatch();
							}
							permissionsInsert.executeBatch();
						}
					}
					
					statement.executeUpdate("DROP TABLE warpTable_backup");
					MyWarp.logger.info("Recovering the backup.");
				}
				if (version < 0) {
					statement.executeUpdate("INSERT INTO meta (name, value) VALUES (\"version\", " + TARGET_VERSION + ")");
				} else {
					statement.executeUpdate("UPDATE meta SET value = " + TARGET_VERSION + " WHERE name = \"version\"");
				}
				this.connection.commit();
			} catch (SQLException ex) {
//				try {
//					statement.execute("ROLLBACK");
//				} catch (SQLException e) {
//					MyWarp.logger.severe("Unable to rollback changes!");
//				}
				MyWarp.logger.log(Level.SEVERE, "Warp Load Exception", ex);
			} finally {
				try {
					if (permissionsInsert != null)
						permissionsInsert.close();
					if (convertedWarp != null)
						convertedWarp.close();
					if (statement != null)
						statement.close();
					if (set != null)
						set.close();
				} catch (SQLException ex) {
					MyWarp.logger.severe("Warp Load Exception (on close)");
				}
			}
		}
	}

	private boolean tableExists(String name) {
		ResultSet rs = null;
		try {
			DatabaseMetaData dbm = this.connection.getMetaData();
			rs = dbm.getTables(null, null, name, null);
			if (!rs.next())
				return false;
			return true;
		} catch (SQLException ex) {
			MyWarp.logger.log(Level.SEVERE, "Table Check Exception", ex);
			return false;
		} finally {
			try {
				if (rs != null)
					rs.close();
			} catch (SQLException ex) {
				MyWarp.logger.severe("Table Check SQL Exception (on closing)");
			}
		}
	}
	
	public int getVersion() {
		Statement statement = null;
		int version = -1;
		ResultSet set = null;
		try {
			statement = this.connection.createStatement();
			if (tableExists("meta")) {
				set = statement.executeQuery("SELECT * FROM meta WHERE name = \"version\"");

				if (set.next()) {
					version = set.getInt("value");
				}
			} else {
				MyWarp.logger.info("Meta table doesn't exists... Creating new");
				statement.executeUpdate(VERSION_TABLE);
				this.connection.commit();
				version = -1;
			}
		} catch (SQLException ex) {
			MyWarp.logger.log(Level.SEVERE, "Table Check Exception", ex);
		} finally {
			try {
				if (statement != null) {
					statement.close();
				}
				if (set != null)
					set.close();
			} catch (SQLException ex) {
				MyWarp.logger.severe("Table Check SQL Exception (on closing)");
			}
		}
		return version;
	}
	
	@Override
	public List<Warp> getWarps() {
		List<Warp> result = new ArrayList<Warp>();
		Statement statement = null;
		ResultSet set = null;
		try {
			statement = this.connection.createStatement();
			set = statement.executeQuery("SELECT * FROM permissions");
			Map<Integer, Map<String, EditorPermissions>> m = new HashMap<Integer, Map<String, EditorPermissions>>();
			while (set.next()) {
				int index = set.getInt("id");
				Map<String, EditorPermissions> n = m.get(index);
				if (n == null) {
					n = new HashMap<String, EditorPermissions>();
					m.put(index, n);
				}
				String editor = set.getString("editor");
				EditorPermissions o = n.get(editor.toLowerCase());
				if (o == null) {
					o = new EditorPermissions();
					n.put(editor.toLowerCase(), o);
				}
				int value = set.getInt("value");
				o.put(Permissions.getById(value), true);
			}
			
			if (set != null) {
				set.close();
			}
			
			set = statement.executeQuery("SELECT * FROM warpTable");
			int size = 0;
			int invalidSize = 0;
			while (set.next()) {
				size++;
				int index = set.getInt("id");
				String name = set.getString("name");
				String creator = set.getString("creator");
				World world = server.getWorld(set.getString("world"));
				double x = set.getDouble("x");
				double y = set.getInt("y");
				double z = set.getDouble("z");
				int yaw = set.getInt("yaw");
				int pitch = set.getInt("pitch");
				Location loc = new Location(world, x, y, z, yaw, pitch);
				Visibility visibility = Visibility.parseLevel(set.getInt("publicLevel"));
				String welcomeMessage = set.getString("welcomeMessage");
				Warp warp = new Warp(index, name, creator, loc, visibility, m.get(index), welcomeMessage);
				result.add(warp);
				if (!warp.isValid()) {
					invalidSize++;
				}
			}
			MyWarp.logger.info(size + " warps loaded");
			if (invalidSize > 0) {
				MyWarp.logger.warning(invalidSize + " invalid warps found.");
			}
		} catch (SQLException ex) {
			MyWarp.logger.severe("Warp Load Exception", ex);
		} finally {
			try {
				if (statement != null)
					statement.close();
				if (set != null)
					set.close();
			} catch (SQLException ex) {
				MyWarp.logger.severe("Warp Load Exception (on close)");
			}
		}
		return result;
	}

	@Override
	public void addWarp(Warp... warps) {
		if (warps.length > 0) {
			PreparedStatement ps = null;
			PreparedStatement insertPermissions = null;
			try {		
				ps = this.connection.prepareStatement("INSERT INTO warpTable (id, name, creator, world, x, y, z, yaw, pitch, publicLevel, welcomeMessage) VALUES (?,?,?,?,?,?,?,?,?,?,?)");
				insertPermissions = this.connection.prepareStatement("INSERT INTO permissions (id, editor, value) VALUES (?,?,?)");
				for (Warp warp : warps) {
					ps.setInt(1, warp.index);
					ps.setString(2, warp.name);
					ps.setString(3, warp.creator);
					setLocation(warp.getLocation(), 4, ps);
					ps.setInt(10, warp.visibility.level);
					ps.setString(11, warp.welcomeMessage);
					ps.addBatch();
					
					for (String editor : warp.getEditors()) {
						EditorPermissions ep = warp.getEditorPermissions(editor);
						if (ep != null) {
							for (Entry<Permissions, Boolean> p : ep.entrySet()) {
								if (p.getValue() != null && p.getValue() == true) {
									insertPermissions.setInt(1, warp.index);
									insertPermissions.setString(2, editor);	
									insertPermissions.setInt(3, p.getKey().id);
									insertPermissions.addBatch();
								}
							}							
						}
					}
				}
				ps.executeBatch();
				insertPermissions.executeBatch();				
				
				this.connection.commit();
			} catch (SQLException ex) {
				MyWarp.logger.log(Level.SEVERE, "Warp Insert Exception", ex);
			} finally {
				try {
					if (ps != null) {
						ps.close();
					}
				} catch (SQLException ex) {
					MyWarp.logger.log(Level.SEVERE, "Warp Insert Exception (on close)", ex);
				}
			}
		}
	}

	private static void setLocation(Location location, int offset, PreparedStatement ps) throws SQLException {
		ps.setString(offset++, location.getWorld().getName());
		ps.setDouble(offset++, location.getX());
		ps.setDouble(offset++, location.getY());
		ps.setDouble(offset++, location.getZ());
		ps.setInt(offset++, (int) location.getYaw());
		ps.setInt(offset++, (int) location.getPitch());	
	}

	@Override
	public void deleteWarp(Warp warp) {
		this.updateWarp(warp, "Delete", "DELETE FROM warpTable WHERE id = ?", new UpdateFiller() {
			
			@Override
			public void fillStatement(Warp warp, PreparedStatement statement) throws SQLException {
				statement.setInt(1, warp.index);
			}
		});
	}
	
	private interface UpdateFiller {
		void fillStatement(Warp warp, PreparedStatement statement) throws SQLException;
	}
	
	private void updateWarp(Warp warp, String name, String sql, UpdateFiller filler) {
		PreparedStatement ps = null;
		ResultSet set = null;
		try {
			ps = this.connection.prepareStatement(sql);
			filler.fillStatement(warp, ps);
			ps.executeUpdate();
			this.connection.commit();
		} catch (SQLException ex) {
			MyWarp.logger.log(Level.SEVERE, "Warp " + name + " Exception", ex);
		} finally {
			try {
				if (ps != null) {
					ps.close();
				}
				if (set != null) {
					set.close();
				}
			} catch (SQLException ex) {
				MyWarp.logger.log(Level.SEVERE,	"Warp " + name + " Exception (on close)", ex);
			}
		}
	}

	@Override
	public void updateCreator(Warp warp) {
		this.updateWarp(warp, "Creator", "UPDATE warpTable SET creator = ? WHERE id = ?", new UpdateFiller() {
			
			@Override
			public void fillStatement(Warp warp, PreparedStatement statement) throws SQLException {
				statement.setString(1, warp.creator);
				statement.setInt(2, warp.index);
			}
		});
	}

	@Override
	public void updateMessage(Warp warp) {
		this.updateWarp(warp, "Welcome Message", "UPDATE warpTable SET welcomeMessage = ? WHERE id = ?", new UpdateFiller() {
			
			@Override
			public void fillStatement(Warp warp, PreparedStatement statement) throws SQLException {
				statement.setString(1, warp.welcomeMessage);
				statement.setInt(2, warp.index);
			}
		});
	}

	@Override
	public void updateName(Warp warp) {
		this.updateWarp(warp, "Name", "UPDATE warpTable SET name = ? WHERE id = ?", new UpdateFiller() {
			
			@Override
			public void fillStatement(Warp warp, PreparedStatement statement) throws SQLException {
				statement.setString(1, warp.name);
				statement.setInt(2, warp.index);
			}
		});
	}

	@Override
	public void updateVisibility(Warp warp) {
		this.updateWarp(warp, "Visibility", "UPDATE warpTable SET publicLevel = ? WHERE id = ?", new UpdateFiller() {
			
			@Override
			public void fillStatement(Warp warp, PreparedStatement statement) throws SQLException {
				statement.setInt(1, warp.visibility.level);
				statement.setInt(2, warp.index);
			}
		});
	}

	@Override
	public void updateLocation(Warp warp) {
		this.updateWarp(warp, "Location", "UPDATE warpTable SET world = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ? WHERE id = ?", new UpdateFiller() {
			
			@Override
			public void fillStatement(Warp warp, PreparedStatement statement) throws SQLException {
				Location loc = warp.getLocation();
				SQLiteConnection.setLocation(loc, 1, statement);
				statement.setInt(7, warp.index);
			}
		});
	}

	@Override
	public void updateEditor(Warp warp, String name) {
		PreparedStatement ps = null;
		ResultSet set = null;
		try {
			ps = this.connection.prepareStatement("DELETE FROM permissions WHERE id = ? AND editor = ?");
			ps.executeUpdate();
			
			EditorPermissions p = warp.getEditorPermissions(name);
			if (p != null) {
				ps = this.connection.prepareStatement("INSERT OR IGNORE INTO permissions (id, editor, value) VALUES (?,?,?)");
				
				for (Entry<Permissions, Boolean> entry : p.entrySet()) {
					ps.setInt(1, warp.index);
					ps.setString(2, name);
					ps.setInt(3, entry.getKey().id);
				}
				
				ps.executeUpdate();
			}
			
			this.connection.commit();
		} catch (SQLException ex) {
			MyWarp.logger.log(Level.SEVERE, "Warp Editor Exception", ex);
		} finally {
			try {
				if (ps != null) {
					ps.close();
				}
				if (set != null) {
					set.close();
				}
			} catch (SQLException ex) {
				MyWarp.logger.log(Level.SEVERE,	"Warp Editor Exception (on close)", ex);
			}
		}
	}
	
	public static List<String> processList(String permissions) {
		String[] names = permissions.split(",");
		List<String> ret = new ArrayList<String>();
		for (String name : names) {
			if (name.equals(""))
				continue;
			ret.add(name.trim());
		}
		return ret;
	}

	@Override
	public void load(File file) {
		if (file.exists()) {
			this.initFile(file);
	        this.update();
		} else {
			this.create(file);
		}
	}

	@Override
	public String getFilename() {
		return "warps.db";
	}

	@Override
	public void clear() {
		Statement statement = null;
		ResultSet set = null;
		try {
			statement = this.connection.createStatement();
			statement.execute("DELETE FROM warpTable");
			this.connection.commit();
		} catch (SQLException ex) {
			MyWarp.logger.log(Level.SEVERE, "Table Clear Exception", ex);
		} finally {
			try {
				if (statement != null) {
					statement.close();
				}
				if (set != null)
					set.close();
			} catch (SQLException ex) {
				MyWarp.logger.severe("Table Clear Exception (on closing)");
			}
		}
	}

	@Override
	public void create(File file) {
		this.initFile(file);
		int version = this.getVersion();
		Statement statement = null;
		ResultSet set = null;
		try {
			statement = this.connection.createStatement();
			// Drop warpTable → create new one
			statement.execute("DROP TABLE warpTable");
			statement.execute(WARP_TABLE);
			if (version < 0) {
				statement.executeUpdate("INSERT INTO meta (name, value) VALUES (\"version\", " + TARGET_VERSION + ")");
			} else {
				statement.executeUpdate("UPDATE meta SET value = " + TARGET_VERSION + " WHERE name = \"version\"");
			}
			this.connection.commit();
		} catch (SQLException ex) {
			MyWarp.logger.log(Level.SEVERE, "Table Drop/Create Exception", ex);
			throw newLoadException();
		} finally {
			try {
				if (statement != null) {
					statement.close();
				}
				if (set != null)
					set.close();
			} catch (SQLException ex) {
				MyWarp.logger.severe("Table Drop/Create Exception (on closing)");
				throw newLoadException();
			}
		}
	}
}
