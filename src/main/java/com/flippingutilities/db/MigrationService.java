package com.flippingutilities.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight migration service placeholder.
 * In a full implementation this would migrate data from JSON-based storage
 * to SQLite-backed storage. For now it serves as a hook to indicate migration
 * should occur.
 */
public class MigrationService {
    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);

    public MigrationService() {
        // no-op constructor
    }

    public void migrate() {
        log.info("[MigrationService] Migration started (stub). Implement JSON->SQLITE migration here.");
        // In a complete implementation, perform data migration here.
        log.info("[MigrationService] Migration finished (stub).");
    }
}
