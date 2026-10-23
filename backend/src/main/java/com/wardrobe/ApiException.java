package com.wardrobe;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() { return status; }

    public static ApiException invalidAi() {
        return new ApiException(HttpStatus.BAD_GATEWAY,
                "The AI returned an invalid response. Try again or edit your wardrobe manually.");
    }

    public static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "Ollama or the configured model is unavailable. Check Ollama and the model; manual wardrobe management still works.");
    }
}