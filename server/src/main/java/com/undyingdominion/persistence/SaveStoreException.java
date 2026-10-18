package com.undyingdominion.persistence;

/** The place saves are kept in could not be read or written. */
public final class SaveStoreException extends RuntimeException {

    public SaveStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
