package ru.voidrp.gamesync.service;

import java.io.IOException;

public final class BackendApiException extends IOException {
    private final int statusCode;

    public BackendApiException(int statusCode, String userMessage) {
        super(userMessage);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
