package com.flippingutilities.ui.uiutilities;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Owns the disposable data directory used by the standalone plugin sandbox. */
public final class SandboxData implements AutoCloseable {
    private final Path source;
    private final Path temporaryHome;
    private final boolean databaseSource;
    private boolean closed;

    private SandboxData(Path source, Path temporaryHome, boolean databaseSource) {
        this.source = source;
        this.temporaryHome = temporaryHome;
        this.databaseSource = databaseSource;
    }

    public static Path defaultSource() {
        return Paths.get(System.getProperty("user.home"), ".runelite");
    }

    /**
     * Accepts a RuneLite directory, a plugin data directory, or an individual database.
     * Only plugin saves and RuneLite settings are copied; caches and session files are excluded.
     */
    public static SandboxData copyOf(Path source) throws Exception {
        Path absoluteSource = source.toAbsolutePath().normalize();
        if (!Files.exists(absoluteSource, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Data source does not exist: " + absoluteSource
                + ". Start RuneLite first, or use --source with a RuneLite folder, flipping folder, or SQLite database.");
        }
        requireOrdinaryPath(absoluteSource);
        boolean databaseSource = Files.isRegularFile(absoluteSource);
        if (!databaseSource && !Files.isDirectory(absoluteSource)) {
            throw new IOException("Data source must be a directory or SQLite database: " + absoluteSource);
        }

        Path temporaryHome = Files.createTempDirectory("flipping-sandbox-");
        SandboxData snapshot = new SandboxData(absoluteSource, temporaryHome, databaseSource);
        try {
            Path destination = Files.createDirectories(snapshot.getRuneLiteDirectory().resolve("flipping"));
            if (databaseSource) {
                copyDatabase(absoluteSource, destination.resolve("flipping.db"));
            } else {
                Path nestedPluginDirectory = absoluteSource.resolve("flipping");
                Path pluginDirectory = Files.exists(nestedPluginDirectory, LinkOption.NOFOLLOW_LINKS)
                    ? nestedPluginDirectory : absoluteSource;
                requireOrdinaryPath(pluginDirectory);
                if (!Files.isDirectory(pluginDirectory)) {
                    throw new IOException("Plugin data path is not a directory: " + pluginDirectory);
                }
                copyPluginDirectory(pluginDirectory, destination);
                Path settings = absoluteSource.resolve("settings.properties");
                if (Files.exists(settings, LinkOption.NOFOLLOW_LINKS)) {
                    copyFile(settings, snapshot.getRuneLiteDirectory().resolve("settings.properties"));
                }
            }
            return snapshot;
        } catch (Exception | Error failure) {
            try {
                snapshot.close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static void copyPluginDirectory(Path source, Path destination) throws Exception {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(source)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if ("flipping.db".equals(name)) {
                    requireOrdinaryPath(entry);
                    copyDatabase(entry, destination.resolve(name));
                } else if (name.endsWith(".json") || name.endsWith(".json.pre-migration")
                    || "flipping.db.needs-resync".equals(name)) {
                    copyFile(entry, destination.resolve(name));
                }
            }
        }
    }

    private static void copyFile(Path source, Path destination) throws IOException {
        requireOrdinaryPath(source);
        if (!Files.isRegularFile(source)) {
            throw new IOException("Expected a regular data file: " + source);
        }
        // Do not copy attributes: a read-only source should still be editable in the sandbox.
        Files.copy(source, destination);
    }

    private static void requireOrdinaryPath(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Symbolic links are not supported as sandbox data: " + path
                + ". Select the actual file or directory instead.");
        }
    }

    private static void copyDatabase(Path source, Path destination) throws SQLException, IOException {
        if (!Files.isRegularFile(source)) {
            throw new IOException("Expected a regular SQLite database: " + source);
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(5000);
        // SQLite's backup API sees committed WAL records without checkpointing, migrating,
        // or opening the source for writes. Raw copies of a live DB and its WAL can disagree.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source.toUri(), config.toProperties())) {
            int result = ((SQLiteConnection) connection).getDatabase().backup("main", destination.toString(), null);
            if (result != 0) {
                throw new SQLException("Unable to snapshot SQLite database " + source + " (SQLite result " + result + ")");
            }
        }
    }

    public Path getRuneLiteDirectory() {
        return temporaryHome.resolve(".runelite");
    }

    public Path getSource() {
        return source;
    }

    public boolean isDatabaseSource() {
        return databaseSource;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        if (Files.exists(temporaryHome, LinkOption.NOFOLLOW_LINKS)) {
            // Never follow links, including any links a developer adds inside the sandbox.
            Files.walkFileTree(temporaryHome, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        closed = true;
    }
}
