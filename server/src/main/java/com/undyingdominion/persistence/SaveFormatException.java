package com.undyingdominion.persistence;

/** A stored save that this version of the game can't read. */
public final class SaveFormatException extends RuntimeException {

    public SaveFormatException(String message) {
        super(message);
    }

    public SaveFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
