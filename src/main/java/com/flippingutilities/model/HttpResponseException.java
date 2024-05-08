package com.flippingutilities.model;

import lombok.Getter;

import java.io.IOException;

@Getter
public class HttpResponseException extends IOException {
    private final int responseCode;
    private final String responseMessage;

    public HttpResponseException(int responseCode, String message) {
        super(message);
        this.responseCode = responseCode;
        this.responseMessage = message;
    }

}
