package com.undyingdominion.net;

/** A client message the server could not act on. The message is sent back to the client verbatim. */
public class ProtocolException extends Exception {

    public ProtocolException(String message) {
        super(message);
    }
}
