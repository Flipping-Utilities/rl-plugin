package com.flippingutilities.controller;

import com.flippingutilities.utilities.SlotState;
import com.flippingutilities.utilities.SlotsUpdate;
import com.flippingutilities.utilities.TokenResponse;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * This class is responsible for wrapping the api. All api interactions must go through this class.
 */
@Slf4j
public class ApiRequestHandler {
    FlippingPlugin plugin;
    OkHttpClient httpClient;

    public ApiRequestHandler(FlippingPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = plugin.getHttpClient();
    }

    public static String getBaseUrl() {
        if (System.getenv("apiurl") == null) {
            //TODO replace with actual url of course
            return "https://discord.com/login/";
        }
        else {
            return System.getenv("apiurl");
        }
    }

    public static String getSlotUpdateUrl() {
        return getBaseUrl() + "something";
    }

    public static String getTokenUrl() {
        return getBaseUrl() + "auth/token";
    }

    //don't care about the response body (if there is any), so we just return the entire response in case the caller
    //wants something.
    public CompletableFuture<Response> updateGeSlots(SlotsUpdate slotsUpdate) {
        String jwt = plugin.getDataHandler().getAccountWideData().getJwt();
        String json = new Gson().newBuilder().setDateFormat(SlotState.DATE_FORMAT).create().toJson(slotsUpdate);
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), json);
        Request request = new Request.Builder().
                header("User-Agent", "FlippingUtilities").
                header("Authorization", "bearer" + jwt).
                post(body).
                url(getSlotUpdateUrl()).
                build();
        return getResponseFuture(request);
    }

    /**
     * Authenticates with the api using the token provided by Flopper and returns the jwt which is used to communicate
     * with the other api endpoints.
     *
     * This method should really only be called by the ApiLoginHandler class as that class is responsible for handling
     * logins and running any action that components interested in subscribing to logins have provided it.
     * If another component is calling this method and attempting to login, the login subscribers won't be notified,
     * unless we want duplicate code to notify the subscribers....So, the central place for logins should be the
     * ApiLoginHandler class and as such only it should call this method.
     * @param token the token to authenticate with
     * @return the jwt meant to be sent on every subsequent request
     */
    public CompletableFuture<String> loginWithToken(String token) {
        Request request = new Request.Builder().
                header("User-Agent", "FlippingUtilities").
                header("Authorization", token).
                url(getTokenUrl()).
                build();
        CompletableFuture<Response> response = getResponseFuture(request);
        return response.thenApply((r) -> {
            try (ResponseBody responseBody = r.body()) {
                TokenResponse tokenResponse = new Gson().fromJson(responseBody.string(), TokenResponse.class);
                String jwt = tokenResponse.getAccess_token();
                if (jwt == null) {
                    throw new JwtNullException(request, r);
                }
                return jwt;
            } catch (IOException e) {
                throw new ResponseBodyReadingException(request, r, e);
            }
        });
    }

    /**
     * CompletableFuture API is wack...but it is much more flexible then the only callback way that okhttp offers. And
     * in the case where we need the result of the response from many requests to be joined together, the callback way
     * is not going to be sufficient as there is no way to "wait" for all the callbacks to finish. So this method
     * exists to bridge the gap between the callback only interface okhttp offers and Futures, specifically,
     * CompletableFutures.
     */
    public CompletableFuture<Response> getResponseFuture(Request request) {
        CompletableFuture<Response> future = new CompletableFuture<>();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(new RequestFailure(request, e));
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (!response.isSuccessful()) {
                    future.completeExceptionally(new BadStatusCodeException(request, response));
                    return;
                }
                future.complete(response);
                }
        });
        return future;
    }
}


class BadStatusCodeException extends Exception {
    Request request;
    Response response;
    public BadStatusCodeException(Request request, Response response) {
        super(String.format("Request: %s resulted in bad status code in response: %s", request, response));
        this.request = request;
        this.response = response;
    }
}

class JwtNullException extends RuntimeException {
    Request request;
    Response response;
    public JwtNullException(Request request, Response response) {
        super(String.format("jwt from response: %s is null. Request: %s", response, request));
        this.request = request;
        this.response = response;
    }
}

class ResponseBodyReadingException extends RuntimeException {
    Request request;
    Response response;
    public ResponseBodyReadingException(Request request, Response response, IOException e) {
        super(String.format("Could not read response body for response: %s from request: %s, exception: %s", response, request, e));
        this.request = request;
        this.response = response;
    }
}

class RequestFailure extends Exception {
    Request request;
    public RequestFailure(Request request, IOException e) {
        super(String.format("Request failure for request: %s, either the request failed to send or no response was received. Resulting" +
                "IOException: %s", request.toString(), e.toString()));
        this.request = request;
    }
}
