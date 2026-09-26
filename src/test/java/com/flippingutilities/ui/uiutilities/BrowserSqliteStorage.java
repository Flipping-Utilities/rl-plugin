package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.db.SqliteStorage;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Runs the unchanged production SQLite DAO and migration against a browser-owned database. */
public final class BrowserSqliteStorage extends SqliteStorage {
    private static final Gson GSON = new Gson();
    private final JdbcConnection jdbc;
    private final Connection connection;

    /**
     * query -> {columns, rows}; update -> {changes, lastInsertId}; batch -> {counts};
     * control (BEGIN, COMMIT, ROLLBACK, CLOSE) -> {}. Errors return {error}.
     * All bound integers and returned int64 values use decimal strings; a batch is
     * an array of parameter arrays and must execute in one bridge call.
     */
    @FunctionalInterface
    public interface Bridge {
        String execute(String operation, String sql, String parametersJson) throws SQLException;
    }

    private static native String execute(String operation, String sql, String parametersJson) throws SQLException;

    public static BrowserSqliteStorage open(File databaseFile) {
        return new BrowserSqliteStorage(databaseFile, BrowserSqliteStorage::execute);
    }

    public BrowserSqliteStorage(File databaseFile, Bridge bridge) {
        super(databaseFile);
        jdbc = new JdbcConnection(Objects.requireNonNull(bridge));
        connection = proxy(Connection.class, jdbc);
    }

    @Override public synchronized Connection getConnection() throws SQLException {
        jdbc.requireOpen();
        return connection;
    }

    @Override public synchronized void close() {
        try { connection.close(); }
        catch (SQLException failure) { throw new IllegalStateException("Could not close browser SQLite", failure); }
        finally { super.close(); }
    }

    private final class JdbcConnection extends Handle {
        private final Bridge bridge;
        private boolean autoCommit = true;
        private boolean transaction;

        JdbcConnection(Bridge bridge) { this.bridge = bridge; }

        JsonObject request(String operation, String sql, Object parameters) throws SQLException {
            requireOpen();
            final String response;
            try { response = bridge.execute(operation, sql, GSON.toJson(parameters)); }
            catch (RuntimeException failure) { throw new SQLException("Browser SQLite bridge failed", failure); }
            final JsonObject result;
            try { result = new JsonParser().parse(response).getAsJsonObject(); }
            catch (RuntimeException failure) { throw new SQLException("Malformed browser SQLite response", failure); }
            if (result.has("error")) throw new SQLException(result.get("error").getAsString());
            return result;
        }

        void control(String sql) throws SQLException { request("control", sql, Collections.emptyList()); }

        void beforeStatement() throws SQLException {
            requireOpen();
            // JDBC keeps autoCommit=false after a commit/rollback. The next statement
            // starts a new transaction; setting false again must not issue nested BEGIN.
            if (!autoCommit && !transaction) {
                control("BEGIN");
                transaction = true;
            }
        }

        @Override Object call(Method method, Object[] args) throws SQLException {
            switch (method.getName()) {
                case "getAutoCommit": return autoCommit;
                case "setAutoCommit":
                    boolean requested = (Boolean) args[0];
                    if (requested && !autoCommit && transaction) {
                        control("COMMIT");
                        transaction = false;
                    }
                    autoCommit = requested;
                    return null;
                case "commit":
                case "rollback":
                    if (autoCommit) throw new SQLException("Cannot commit or roll back in auto-commit mode");
                    if (transaction) {
                        control(method.getName().equals("commit") ? "COMMIT" : "ROLLBACK");
                        transaction = false;
                    }
                    return null;
                case "isValid": return true;
                case "createStatement":
                    if (args.length == 0) return proxy(Statement.class, new JdbcStatement(null, false));
                    break;
                case "prepareStatement":
                    if (args.length == 1 || (args.length == 2 && args[1] instanceof Integer
                        && ((Integer) args[1] == Statement.RETURN_GENERATED_KEYS || (Integer) args[1] == Statement.NO_GENERATED_KEYS))) {
                        return proxy(PreparedStatement.class, new JdbcStatement((String) args[0],
                            args.length == 2 && (Integer) args[1] == Statement.RETURN_GENERATED_KEYS));
                    }
                    break;
                default: break;
            }
            throw unsupported(method);
        }

        @Override void closeHandle() throws SQLException {
            try {
                if (transaction) {
                    control("ROLLBACK");
                    transaction = false;
                }
            } finally { control("CLOSE"); }
        }
    }

    private final class JdbcStatement extends Handle {
        private final String preparedSql;
        private final boolean generatedKeys;
        private final Object[] parameters;
        private final boolean[] bound;
        private final List<Object[]> batch = new ArrayList<>();
        private ResultSet result;
        private String insertedId;
        private int updateCount = -1;

        JdbcStatement(String sql, boolean generatedKeys) {
            preparedSql = sql;
            this.generatedKeys = generatedKeys;
            int count = sql == null ? 0 : (int) sql.chars().filter(character -> character == '?').count();
            parameters = new Object[count];
            bound = new boolean[count];
        }

        @Override Object call(Method method, Object[] args) throws SQLException {
            jdbc.requireOpen();
            String name = method.getName();
            if (preparedSql != null && args.length == 2
                && Arrays.asList("setString", "setInt", "setLong", "setBoolean", "setNull").contains(name)) {
                int index = (Integer) args[0] - 1;
                if (index < 0 || index >= parameters.length) throw new SQLException("Invalid SQL parameter index");
                parameters[index] = name.equals("setNull") ? null : name.equals("setBoolean")
                    ? ((Boolean) args[1] ? "1" : "0") : args[1] == null ? null : args[1].toString();
                bound[index] = true;
                return null;
            }
            if (name.equals("clearParameters") && args.length == 0) {
                Arrays.fill(parameters, null);
                Arrays.fill(bound, false);
                return null;
            }
            if (name.equals("addBatch") && args.length == 0 && preparedSql != null) {
                requireBound();
                batch.add(parameters.clone());
                return null;
            }
            if (name.equals("clearBatch") && args.length == 0) { batch.clear(); return null; }
            if (name.equals("executeBatch") && args.length == 0 && preparedSql != null) {
                clearResult();
                if (batch.isEmpty()) return new int[0];
                jdbc.beforeStatement();
                try {
                    JsonObject response = jdbc.request("batch", preparedSql, batch);
                    JsonArray counts = response.getAsJsonArray("counts");
                    if (counts == null || counts.size() != batch.size()) throw new SQLException("Invalid browser SQLite batch counts");
                    int[] changed = new int[counts.size()];
                    for (int index = 0; index < changed.length; index++) changed[index] = counts.get(index).getAsInt();
                    return changed;
                } catch (RuntimeException failure) { throw new SQLException("Malformed browser SQLite batch response", failure); }
                finally { batch.clear(); }
            }
            if (name.equals("getGeneratedKeys") && args.length == 0) {
                JsonObject keys = new JsonObject();
                keys.add("columns", GSON.toJsonTree(Collections.singletonList("last_insert_rowid()")));
                keys.add("rows", GSON.toJsonTree(insertedId == null ? Collections.emptyList()
                    : Collections.singletonList(Collections.singletonList(insertedId))));
                return BrowserSqliteImporter.resultSet(GSON.toJson(keys));
            }
            if (name.equals("getConnection") && args.length == 0) return connection;
            if (name.equals("getResultSet") && args.length == 0) return result;
            if (name.equals("getUpdateCount") && args.length == 0) return updateCount;
            if (Arrays.asList("executeQuery", "executeUpdate", "execute").contains(name)
                && ((preparedSql != null && args.length == 0) || (preparedSql == null && args.length == 1 && args[0] instanceof String))) {
                String sql = preparedSql == null ? (String) args[0] : preparedSql;
                requireBound();
                clearResult();
                jdbc.beforeStatement();
                if (name.equals("executeUpdate")) {
                    JsonObject response = jdbc.request("update", sql, parameters);
                    try {
                        updateCount = response.get("changes").getAsInt();
                        if (updateCount < 0) throw new IllegalArgumentException("Negative update count");
                        if (generatedKeys && updateCount > 0) insertedId = response.get("lastInsertId").getAsString();
                    } catch (RuntimeException failure) { throw new SQLException("Malformed browser SQLite update response", failure); }
                    return updateCount;
                }
                JsonObject response = jdbc.request("query", sql, parameters);
                result = BrowserSqliteImporter.resultSet(GSON.toJson(response));
                if (name.equals("executeQuery")) return result;
                boolean hasRows = response.getAsJsonArray("columns").size() > 0;
                if (!hasRows) updateCount = 0;
                return hasRows;
            }
            throw unsupported(method);
        }

        private void requireBound() throws SQLException {
            for (boolean present : bound) if (!present) throw new SQLException("Unbound SQL parameter");
        }

        private void clearResult() throws SQLException {
            if (result != null) result.close();
            result = null;
            insertedId = null;
            updateCount = -1;
        }

        @Override void closeHandle() throws SQLException { clearResult(); batch.clear(); }
    }

    private abstract static class Handle implements InvocationHandler {
        private boolean closed;

        void requireOpen() throws SQLException { if (closed) throw new SQLException("Browser SQLite handle is closed"); }

        @Override public synchronized Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            Object[] args = arguments == null ? new Object[0] : arguments;
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "toString": return "Browser SQLite " + proxy.getClass().getInterfaces()[0].getSimpleName();
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    default: throw unsupported(method);
                }
            }
            if (method.getName().equals("close") && args.length == 0) {
                if (!closed) try { closeHandle(); } finally { closed = true; }
                return null;
            }
            if (method.getName().equals("isClosed") && args.length == 0) return closed;
            if (method.getName().equals("isValid") && closed) return false;
            requireOpen();
            return call(method, args);
        }

        void closeHandle() throws SQLException {}
        abstract Object call(Method method, Object[] args) throws SQLException;
    }

    private static SQLFeatureNotSupportedException unsupported(Method method) {
        return new SQLFeatureNotSupportedException("Unsupported browser SQLite JDBC call: " + method.getName());
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(BrowserSqliteStorage.class.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
