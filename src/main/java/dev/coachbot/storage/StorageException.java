package dev.coachbot.storage;

/** Wraps I/O errors from any {@link StorageBackend} implementation. */
public class StorageException extends Exception {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
