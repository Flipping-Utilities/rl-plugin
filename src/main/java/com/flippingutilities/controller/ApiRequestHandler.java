package com.flippingutilities.controller;

import com.flippingutilities.model.AccountStatus;
import com.flippingutilities.model.HttpResponseException;
import com.flippingutilities.model.Suggestion;
import com.flippingutilities.utilities.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * This class is responsible for wrapping the api. All api interactions must go through this class.
 */
@Slf4j
public class ApiRequestHandler {
    FlippingPlugin plugin;
    OkHttpClient httpClient;
    private static String BASE_API_URL = System.getenv("OSRS_CLOUD_API_BASE_URL") != null ? System.getenv("OSRS_CLOUD_API_BASE_URL")  : "https://api.osrs.cloud/v1/";
    public static String SLOT_FETCH_URL = BASE_API_URL + "ge/slots";
    public static String SLOT_UPDATE_URL = BASE_API_URL + "ge/slots/update";
    public static String JWT_HEALTH_URL = BASE_API_URL + "auth/jwt/health";
    public static String JWT_REFRESH_URL = BASE_API_URL + "auth/refresh";
    public static String USER_URL = BASE_API_URL + "user/self";
    public static String ACCOUNT_URL = BASE_API_URL + "account/self";
    public static String ACCOUNT_REGISTRATION_URL = BASE_API_URL + "account/register";
    public static String TOKEN_URL = BASE_API_URL + "auth/token";

    public ApiRequestHandler(FlippingPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = plugin.getHttpClient();
    }

    public CompletableFuture<User> getUser() {
        if (!plugin.getApiAuthHandler().isHasValidJWT()) {
            return null;
        }
        String jwt = plugin.getDataHandler().viewAccountWideData().getJwt();
        Request request = new Request.Builder().
            header("User-Agent", "FlippingUtilities").
            header("Authorization", "bearer " + jwt).
            url(USER_URL).
            build();
        return getResponseFuture(request, new TypeToken<ApiResponse<User>>(){}).thenApply(r -> r.data);
    }

    public CompletableFuture<List<OsrsAccount>> getUserAccounts() {
        if (!plugin.getApiAuthHandler().isHasValidJWT()) {
            return null;
        }
        String jwt = plugin.getDataHandler().viewAccountWideData().getJwt();
        Request request = new Request.Builder().
                header("User-Agent", "FlippingUtilities").
                header("Authorization", "bearer " + jwt).
                url(ACCOUNT_URL).
                build();
        return getResponseFuture(request, new TypeToken<ApiResponse<List<OsrsAccount>>>(){}).thenApply(r -> r.data);
    }

    public CompletableFuture<OsrsAccount> registerNewAccount(String rsn) {
        if (!plugin.getApiAuthHandler().isHasValidJWT()) {
            return null;
        }
        String jwt = plugin.getDataHandler().viewAccountWideData().getJwt();
        HttpUrl url = HttpUrl.parse(ACCOUNT_REGISTRATION_URL).newBuilder().addQueryParameter("rsn", rsn).build();
        Request request = new Request.Builder().
                header("User-Agent", "FlippingUtilities").
                header("Authorization", "bearer " + jwt).
                url(url).
                build();
        CompletableFuture<ApiResponse<OsrsAccount>> response = getResponseFuture(request, new TypeToken<ApiResponse<OsrsAccount>>(){});
        return response.thenApply(r -> r.data);
    }

    public CompletableFuture<String> refreshJwt(String jwtString) {
        if (!plugin.getApiAuthHandler().isHasValidJWT()) {
            return null;
        }
        Request request = new Request.Builder().
                header("User-Agent", "FlippingUtilities").
                header("Authorization", "bearer " + jwtString).
                url(JWT_REFRESH_URL).
                build();
        return getResponseFuture(request, new TypeToken<ApiResponse<TokenResponse>>(){}).thenApply(r -> r.data.getAccess_token());
    }

    //don't care about the response body (if there is any), so we just return the entire response in case the caller
    //wants something.
    public CompletableFuture<Integer> updateGeSlots(AccountSlotsUpdate accountSlotsUpdate) {
        if (!plugin.getApiAuthHandler().isHasValidJWT()) {
            return null;
        }
        String jwt = plugin.getDataHandler().viewAccountWideData().getJwt();
        String json = plugin.gson.newBuilder().setDateFormat(SlotState.DATE_FORMAT).create().toJson(accountSlotsUpdate);
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), json);
        Request request = new Request.Builder().
                header("User-Agent", "FlippingUtilities").
                header("Authorization", "bearer " + jwt).
                post(body).
                url(SLOT_UPDATE_URL).
                build();

        return getResponseFuture(request, new TypeToken<ApiResponse<Integer>>(){}).thenApply(r -> r.data);
    }

    /**
     * Authenticates with the api using the token provided by Flopper and returns the jwt which is used to communicate
     * with the other api endpoints.
     * <p>
     * This method should really only be called by the ApiLoginHandler class as that class is responsible for handling
     * logins and running any action that components interested in subscribing to logins have provided it.
     * If another component is calling this method and attempting to login, the login subscribers won't be notified,
     * unless we want duplicate code to notify the subscribers....So, the central place for logins should be the
     * ApiLoginHandler class and as such only it should call this method.
     *
     * @param token the token to authenticate with
     * @return the jwt meant to be sent on every subsequent request
     */
    public CompletableFuture<String> loginWithToken(String token) {
        //Cannot have the JWT check like we do with the other methods because this is the method that is triggered
        //when a user clicks the "Login" button on the login panel, so a user won't have a JWT at that point.
        String json = plugin.gson.toJson(Collections.singletonMap("token", token));
        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"), json);
        Request request = new Request.Builder().
                header("User-Agent", "FlippingUtilities").
                post(body).
                url(TOKEN_URL).
                build();
        return getResponseFuture(request, new TypeToken<ApiResponse<TokenResponse>>(){}).thenApply(r -> r.data.getAccess_token());
    }

    /**
     * CompletableFuture API is wack...but it is much more flexible then the only callback way that okhttp offers. And
     * in the case where we need the result of the response from many requests to be joined together, the callback way
     * is not going to be sufficient as there is no way to "wait" for all the callbacks to finish. So this method
     * exists to bridge the gap between the callback only interface okhttp offers and Futures, specifically,
     * CompletableFutures.
     */
    public <T> CompletableFuture<ApiResponse<T>> getResponseFuture(Request request, TypeToken<ApiResponse<T>> type) {
        CompletableFuture<ApiResponse<T>> future = new CompletableFuture<>();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(new RequestFailureException(request, e));
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (!response.isSuccessful()) {
                    future.completeExceptionally(new BadStatusCodeException(request, response, getResponseBody(response)));
                }
                else {
                    try {
                        String body =  response.body().string();
                        ApiResponse<T> apiResponse = plugin.gson.fromJson(body, type.getType());
                        if (apiResponse == null) {
                            future.completeExceptionally(new NullDtoException(request, response, type.toString()));
                        }
                        else if (apiResponse.errors.size() > 0) {
                            //TODO better exception here
                            future.completeExceptionally(new BadStatusCodeException(request, response, body));
                        }
                        else {
                            future.complete(apiResponse);
                        }
                        response.close();
                    }
                    catch (IOException e) {
                        future.completeExceptionally(new ResponseBodyReadingException(request, response, e));
                        response.close();
                    }
                }
            }
        });
        return future;
    }

    public static String getResponseBody(Response response) {
        try {
            return response.body().string();
        }
        catch (Exception e) {
            return "Could not fetch response body";
        }
    }

    public Suggestion getSuggestion(AccountStatus accountStatus) throws IOException {
        JsonObject status = accountStatus.toJson(plugin.gson);
        JsonObject suggestionJson = postJson(status, "suggestion");
        return Suggestion.fromJson(suggestionJson, plugin.gson);
    }

    private JsonObject postJson(JsonObject json, String route) throws HttpResponseException {
        String jwt = plugin.getDataHandler().viewAccountWideData().getJwt();
        RequestBody body = RequestBody.create(MediaType.get("application/json; charset=utf-8"), json.toString());
        Request request = new Request.Builder()
            .url("https://devyeet.me/v1/" + route)
            .addHeader("Authorization", "Bearer " + jwt)
            .post(body)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            JsonObject responseJson = plugin.gson.fromJson(response.body().string(), JsonObject.class);
            if (!response.isSuccessful()) {
                throw new HttpResponseException(response.code(), responseJson.get("message").getAsString());
            }
            return responseJson;
        } catch (HttpResponseException e) {
            throw e;
        } catch (JsonParseException | IOException e) {
            throw new HttpResponseException(-1, e.getMessage());
        }
    }


}


class BadStatusCodeException extends Exception {
    Request request;
    Response response;

    public BadStatusCodeException(Request request, Response response, String body) {
        super(String.format("Request: %s resulted in bad status code in response: %s, body %s", request, response, body));
        this.request = request;
        this.response = response;
    }
}

class NullDtoException extends RuntimeException {
    Request request;
    Response response;

    public NullDtoException(Request request, Response response, String dtoName) {
        super(String.format("%s from response: %s is null. Request: %s", dtoName, response, request));
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

class RequestFailureException extends Exception {
    Request request;

    public RequestFailureException(Request request, IOException e) {
        super(String.format("Request failure for request: %s, either the request failed to send or no response was received. Resulting" +
                "IOException: %s", request.toString(), e.toString()));
        this.request = request;
    }
}

class ApiResponse<T> {
    T data;
    String time;
    List<ApiError> errors;
    List<ApiMessage> messages;
}

class ApiMessage {
    String type;
    String key;
    String message;
}

class ApiError {
    int code;
    String type;
    String key;
    String message;
}
