package com.flippingutilities;

/**
 * Action selector for the "SQLite maintenance" config dropdown.
 * NONE is the idle state; selecting DELETE or REGENERATE triggers the action once
 * and the plugin resets the value back to NONE.
 */
public enum SqliteMaintenanceAction {
	NONE,
	DELETE,
	REGENERATE
}
