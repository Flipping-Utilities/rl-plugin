package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.db.SqliteSchema;
import com.flippingutilities.db.SqliteStorage;
import com.flippingutilities.model.AccountData;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Read a browser-owned SQLite snapshot through the production account reconstruction code. */
public final class BrowserSqliteImporter {
    private static final Gson GSON = new Gson();
    private static final Map<String, Set<String>> REQUIRED_COLUMNS = new LinkedHashMap<>();
    private static final Set<String> READ_QUERIES = new HashSet<>(Arrays.asList(
        "SELECT display_name FROM accounts ORDER BY display_name",
        "SELECT id FROM accounts WHERE display_name = ?",
        "SELECT session_start, accumulated_time FROM accounts WHERE id = ?",
        "SELECT slot_index, offer_json, history_visible FROM active_slots WHERE account_id = ?",
        "SELECT item_id, offer_json FROM trades WHERE account_id = ? ORDER BY item_id, timestamp, id",
        "SELECT id, recipe_key, coin_cost, timestamp FROM recipe_flips WHERE account_id = ? ORDER BY recipe_key, timestamp",
        "SELECT item_id, offer_uuid, amount_consumed, offer_json FROM recipe_flip_inputs WHERE recipe_flip_id = ?",
        "SELECT item_id, offer_uuid, amount_consumed, offer_json FROM recipe_flip_outputs WHERE recipe_flip_id = ?",
        "SELECT item_id, is_favorite, favorite_code FROM item_favorites WHERE account_id = ?",
        "SELECT item_id, is_visible FROM item_visibility WHERE account_id = ?",
        "SELECT item_id, next_refresh, items_bought, items_bought_complete FROM ge_limit_state WHERE account_id = ?",
        "PRAGMA user_version", "PRAGMA quick_check", "PRAGMA foreign_key_check"
    ));

    static {
        requireColumns("accounts", "id", "display_name", "player_id", "session_start", "accumulated_time");
        requireColumns("active_slots", "account_id", "slot_index", "offer_uuid", "offer_json", "history_visible");
        requireColumns("trades", "id", "account_id", "item_id", "uuid", "timestamp", "qty", "price", "is_buy", "offer_json");
        requireColumns("recipe_flips", "id", "account_id", "timestamp", "recipe_key", "coin_cost", "natural_key");
        for (String table : Arrays.asList("recipe_flip_inputs", "recipe_flip_outputs")) {
            requireColumns(table, "id", "recipe_flip_id", "item_id", "offer_uuid", "amount_consumed", "offer_json");
        }
        requireColumns("settings", "key", "value");
        requireColumns("ge_limit_state", "id", "account_id", "item_id", "next_refresh", "items_bought", "items_bought_complete");
        requireColumns("item_favorites", "id", "account_id", "item_id", "is_favorite", "favorite_code");
        requireColumns("item_visibility", "account_id", "item_id", "is_visible");
    }

    private BrowserSqliteImporter() {}

    /**
     * Synchronous bridge contract: parameters are a JSON array (integers are decimal strings); response is
     * {"columns":["name",...],"rows":[["123",null,"text",...],...]}.
     * Return SQLite integers as decimal strings so JavaScript never rounds int64 values.
     * The bridge owns the database and must hold one stable snapshot throughout load().
     */
    @FunctionalInterface
    public interface Query {
        String query(String sql, String parametersJson) throws SQLException;
    }

    /** The page supplies Java_com_flippingutilities_ui_uiutilities_BrowserSqliteImporter_query. */
    private static native String query(String sql, String parametersJson) throws SQLException;

    public static Map<String, AccountData> load() throws SQLException {
        return load(BrowserSqliteImporter::query);
    }

    /** Returns models only: no files, schema initialization, migrations or writes. */
    public static Map<String, AccountData> load(Query query) throws SQLException {
        Connection connection = connection(Objects.requireNonNull(query));
        try {
            validateSchema(connection);
            SqliteStorage storage = new SqliteStorage(new File("browser-import.db")) {
                @Override public synchronized Connection getConnection() { return connection; }
            };
            Map<String, AccountData> accounts = new LinkedHashMap<>();
            for (String account : storage.listAccounts()) {
                validateAccountName(account);
                accounts.put(account, storage.loadAccount(account));
            }
            return accounts;
        } finally {
            connection.close();
        }
    }

    private static void requireColumns(String table, String... columns) {
        REQUIRED_COLUMNS.put(table, new HashSet<>(Arrays.asList(columns)));
        READ_QUERIES.add("PRAGMA table_info(" + table + ")");
    }

    private static void validateSchema(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA user_version");
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || rows.getInt(1) != SqliteSchema.SCHEMA_VERSION) {
                throw new SQLException("Unsupported SQLite schema: this browser importer requires version " + SqliteSchema.SCHEMA_VERSION);
            }
        }
        for (Map.Entry<String, Set<String>> table : REQUIRED_COLUMNS.entrySet()) {
            Set<String> missing = new HashSet<>(table.getValue());
            try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(" + table.getKey() + ")");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) missing.remove(rows.getString("name"));
            }
            if (!missing.isEmpty()) {
                throw new SQLException("Unsupported SQLite table " + table.getKey() + ": missing columns " + missing);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA quick_check");
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || !"ok".equals(rows.getString(1)) || rows.next()) {
                throw new SQLException("SQLite integrity check failed");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA foreign_key_check");
             ResultSet rows = statement.executeQuery()) {
            if (rows.next()) throw new SQLException("SQLite contains broken foreign-key references");
        }
    }

    private static void validateAccountName(String name) throws SQLException {
        String lower = name.toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
            || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0
            || lower.equals("accountwide") || lower.equals("trades")
            || lower.endsWith(".backup") || lower.endsWith(".special")) {
            throw new SQLException("Account name cannot be represented as a sandbox JSON snapshot");
        }
    }

    private static Connection connection(Query query) {
        return proxy(Connection.class, new Handle() {
            @Override Object call(Method method, Object[] args) throws SQLException {
                if (method.getName().equals("prepareStatement") && args.length == 1 && args[0] instanceof String) {
                    String sql = ((String) args[0]).trim().replaceAll("\\s+", " ");
                    if (!READ_QUERIES.contains(sql)) throw new SQLException("Browser import does not allow SQL: " + sql);
                    return statement(query, sql);
                }
                throw unsupported(method);
            }
        });
    }

    private static PreparedStatement statement(Query query, String sql) {
        int parameterCount = (int) sql.chars().filter(c -> c == '?').count();
        Object[] parameters = new Object[parameterCount];
        boolean[] bound = new boolean[parameterCount];
        return proxy(PreparedStatement.class, new Handle() {
            @Override Object call(Method method, Object[] args) throws SQLException {
                String name = method.getName();
                if ((name.equals("setInt") || name.equals("setLong") || name.equals("setString")) && args.length == 2) {
                    int index = (Integer) args[0] - 1;
                    if (index < 0 || index >= parameters.length) throw new SQLException("Invalid SQL parameter index");
                    parameters[index] = name.equals("setString") ? args[1] : args[1].toString();
                    bound[index] = true;
                    return null;
                }
                if (name.equals("executeQuery") && args.length == 0) {
                    for (boolean present : bound) if (!present) throw new SQLException("Unbound SQL parameter");
                    return resultSet(query.query(sql, GSON.toJson(parameters)));
                }
                throw unsupported(method);
            }
        });
    }

    private static ResultSet resultSet(String response) throws SQLException {
        final JsonArray columns;
        final JsonArray rows;
        try {
            JsonObject result = new JsonParser().parse(response).getAsJsonObject();
            columns = result.getAsJsonArray("columns");
            rows = result.getAsJsonArray("rows");
            if (columns == null || rows == null) throw new IllegalArgumentException("Missing columns or rows");
            Set<String> names = new HashSet<>();
            for (JsonElement column : columns) {
                if (!column.isJsonPrimitive() || !column.getAsJsonPrimitive().isString()
                    || !names.add(column.getAsString().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Invalid or duplicate result column");
                }
            }
            for (JsonElement row : rows) {
                if (!row.isJsonArray() || row.getAsJsonArray().size() != columns.size()) {
                    throw new IllegalArgumentException("Invalid result row width");
                }
                for (JsonElement cell : row.getAsJsonArray()) {
                    if (!cell.isJsonNull() && !cell.isJsonPrimitive()) throw new IllegalArgumentException("Invalid result cell");
                }
            }
        } catch (RuntimeException failure) {
            throw new SQLException("Malformed browser SQLite query response", failure);
        }
        return proxy(ResultSet.class, new Handle() {
            private int position = -1;
            private boolean wasNull;

            @Override Object call(Method method, Object[] args) throws SQLException {
                String name = method.getName();
                if (name.equals("next") && args.length == 0) {
                    if (position < rows.size()) position++;
                    return position < rows.size();
                }
                if (name.equals("wasNull") && args.length == 0) return wasNull;
                if (args.length == 1 && Arrays.asList("getString", "getInt", "getLong", "getBoolean").contains(name)) {
                    if (position < 0 || position >= rows.size()) throw new SQLException("Result cursor is not on a row");
                    int index = columnIndex(args[0], columns);
                    JsonElement value = rows.get(position).getAsJsonArray().get(index);
                    wasNull = value.isJsonNull();
                    if (name.equals("getString")) return wasNull ? null : value.getAsString();
                    try {
                        String integer = wasNull ? "0" : value.getAsString();
                        if (name.equals("getInt")) return Integer.parseInt(integer);
                        if (name.equals("getLong")) return Long.parseLong(integer);
                        return Long.parseLong(integer) != 0;
                    } catch (NumberFormatException failure) {
                        throw new SQLException("Invalid integer in SQLite result column " + columns.get(index).getAsString(), failure);
                    }
                }
                throw unsupported(method);
            }
        });
    }

    private static int columnIndex(Object key, JsonArray columns) throws SQLException {
        if (key instanceof Integer) {
            int index = (Integer) key - 1;
            if (index >= 0 && index < columns.size()) return index;
        } else if (key instanceof String) {
            for (int index = 0; index < columns.size(); index++) {
                if (((String) key).equalsIgnoreCase(columns.get(index).getAsString())) return index;
            }
        }
        throw new SQLException("Unknown SQLite result column: " + key);
    }

    private abstract static class Handle implements InvocationHandler {
        private boolean closed;

        @Override public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            Object[] args = arguments == null ? new Object[0] : arguments;
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "toString": return "Browser SQLite " + proxy.getClass().getInterfaces()[0].getSimpleName();
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    default: throw unsupported(method);
                }
            }
            if (method.getName().equals("close") && args.length == 0) { closed = true; return null; }
            if (method.getName().equals("isClosed") && args.length == 0) return closed;
            if (closed) throw new SQLException("Browser SQLite handle is closed");
            return call(method, args);
        }

        abstract Object call(Method method, Object[] args) throws SQLException;
    }

    private static SQLFeatureNotSupportedException unsupported(Method method) {
        return new SQLFeatureNotSupportedException("Unsupported browser import JDBC call: " + method.getName());
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(BrowserSqliteImporter.class.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
