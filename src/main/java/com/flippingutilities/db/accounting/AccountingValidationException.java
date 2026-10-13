package com.flippingutilities.db.accounting;

/** A rejected user command; the database is healthy and the transaction was rolled back. */
public final class AccountingValidationException extends IllegalArgumentException {
    public AccountingValidationException(String message) { super(message); }
}
