package com.occpl.platform.catalog.api;

/**
 * Unit of a LIMIT feature. Stock units measure a current quantity (seats in use, storage held) and
 * never reset; flow units count events within a reset period (DATABASE.md, usage limit semantics).
 */
public enum FeatureUnit {
    SEATS(true), STORAGE_GB(true), RECORDS(true), API_CALLS(false), TRANSACTIONS(false), DOCUMENTS(false),
    MESSAGES(false), COUNT(false);

    private final boolean stock;

    FeatureUnit(boolean stock) {
        this.stock = stock;
    }

    public boolean isStock() {
        return stock;
    }
}
