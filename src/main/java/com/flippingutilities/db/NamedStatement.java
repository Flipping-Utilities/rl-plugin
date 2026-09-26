package com.flippingutilities.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/** Named bindings for SQLite's :parameter syntax without changing the SQL or interpolating values. */
final class NamedStatement implements AutoCloseable {
    private final PreparedStatement statement;
    private final Map<String, Integer> parameterIndexes;
    private final Set<String> boundParameters = new HashSet<>();

    static NamedStatement prepare(Connection connection, String sql) throws SQLException {
        Map<String, Integer> indexes = parameterIndexes(sql);
        return new NamedStatement(connection.prepareStatement(sql), indexes);
    }

    static NamedStatement prepare(Connection connection, String sql, int generatedKeys) throws SQLException {
        Map<String, Integer> indexes = parameterIndexes(sql);
        return new NamedStatement(connection.prepareStatement(sql, generatedKeys), indexes);
    }

    private NamedStatement(PreparedStatement statement, Map<String, Integer> parameterIndexes) {
        this.statement = statement;
        this.parameterIndexes = parameterIndexes;
    }

    NamedStatement bind(String name, Object value) throws SQLException {
        Integer index = parameterIndexes.get(name);
        if (index == null) {
            throw new SQLException("Unknown SQL parameter: " + name);
        }
        statement.setObject(index, value);
        boundParameters.add(name);
        return this;
    }

    static String placeholders(String prefix, int count) {
        if (prefix.isEmpty() || !isNameStart(prefix.charAt(0)) || count <= 0) {
            throw new IllegalArgumentException("Expected a parameter name and a nonempty list");
        }
        for (int i = 1; i < prefix.length(); i++) {
            if (!isNamePart(prefix.charAt(i))) {
                throw new IllegalArgumentException("Invalid parameter prefix: " + prefix);
            }
        }
        StringJoiner names = new StringJoiner(", ");
        for (int i = 0; i < count; i++) {
            names.add(":" + prefix + i);
        }
        return names.toString();
    }

    NamedStatement bindList(String prefix, Collection<?> values) throws SQLException {
        int index = 0;
        for (Object value : values) {
            bind(prefix + index++, value);
        }
        return this;
    }

    int executeUpdate() throws SQLException {
        requireAllParameters();
        return statement.executeUpdate();
    }

    ResultSet executeQuery() throws SQLException {
        requireAllParameters();
        return statement.executeQuery();
    }

    void addBatch() throws SQLException {
        requireAllParameters();
        statement.addBatch();
    }

    int[] executeBatch() throws SQLException {
        return statement.executeBatch();
    }

    ResultSet getGeneratedKeys() throws SQLException {
        return statement.getGeneratedKeys();
    }

    @Override
    public void close() throws SQLException {
        statement.close();
    }

    private void requireAllParameters() throws SQLException {
        for (String name : parameterIndexes.keySet()) {
            if (!boundParameters.contains(name)) {
                throw new SQLException("Missing SQL parameter: " + name);
            }
        }
    }

    private static Map<String, Integer> parameterIndexes(String sql) throws SQLException {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            if (current == '\'' || current == '"' || current == '`' || current == '[') {
                char closing = current == '[' ? ']' : current;
                while (++i < sql.length()) {
                    if (sql.charAt(i) == closing) {
                        if (current != '[' && i + 1 < sql.length() && sql.charAt(i + 1) == closing) {
                            i++;
                        } else {
                            break;
                        }
                    }
                }
            } else if (current == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (++i < sql.length() && sql.charAt(i) != '\n') {
                    // Parameter-like text inside a line comment is not a binding.
                }
            } else if (current == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? sql.length() : end + 1;
            } else if (current == '?' || current == '@' || current == '$') {
                throw new SQLException("Only :named SQL parameters are supported; found " + current);
            } else if (current == ':') {
                int start = ++i;
                if (start >= sql.length() || !isNameStart(sql.charAt(start))) {
                    throw new SQLException("Invalid named SQL parameter at position " + (start - 1));
                }
                while (i < sql.length() && isNamePart(sql.charAt(i))) {
                    i++;
                }
                if (i < sql.length() && (sql.charAt(i) >= 128 || sql.charAt(i) == '(')) {
                    throw new SQLException("SQL parameter names must contain only ASCII letters, digits and underscores");
                }
                String name = sql.substring(start, i);
                // SQLite assigns one index to each distinct name, in first-use order.
                if (!indexes.containsKey(name)) {
                    indexes.put(name, indexes.size() + 1);
                }
                i--;
            }
        }
        return indexes;
    }

    private static boolean isNameStart(char value) {
        return value == '_' || (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }

    private static boolean isNamePart(char value) {
        return isNameStart(value) || (value >= '0' && value <= '9');
    }
}
