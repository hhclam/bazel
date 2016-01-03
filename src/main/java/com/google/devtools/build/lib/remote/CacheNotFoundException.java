package com.google.devtools.build.lib.remote;

/**
 * An exception to indicate the cache is not found because of an expected
 * problem.
 */
class CacheNotFoundException extends RuntimeException {
    public CacheNotFoundException() {
        super();
    }

    public CacheNotFoundException(String message) {
        super(message);
    }

    public CacheNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
