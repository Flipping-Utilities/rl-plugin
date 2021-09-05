package com.flippingutilities.controller;

import com.flippingutilities.utilities.ApiUrl;
import com.flippingutilities.utilities.TokenResponse;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ApiLoginHandler {
    FlippingPlugin plugin;
    public boolean successfullyLoggedIn;
    //actions to ALWAYS be run on login, the difference between this and the onSuccess runnable passed to login
    //is that this is ALWAYS run on any login, no matter who called login. Whereas the onSuccess is run only for the
    //login initiated by the caller.
    //This allows for flexibility where a caller can specify that they only want an action to be performed on THIS
    //login, rather than all logins ever.
    List<Runnable> alwaysRunOnLogin = new ArrayList<>();

    public ApiLoginHandler(FlippingPlugin plugin) {
        this.plugin = plugin;
    }

    public void addAlwaysRunOnLoginAction(Runnable r) {
        this.alwaysRunOnLogin.add(r);
    }

    public void login() {
        String token = plugin.getDataHandler().getAccountWideData().getToken();
        if (token == null) {
            log.info("no token stored locally, not attempting to login");
            return;
        }
        login(token, () -> {}, () -> {});
    }

    public void login(String token, Runnable onSuccess, Runnable onFailure) {
        log.info("attempting to login!");
        OkHttpClient httpClient = plugin.getHttpClient();
        Request request = new Request.Builder().
                header("User-Agent", "FlippingUtilities").
                header("Authorization", token).
                url(ApiUrl.getTokenUrl()).
                build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.info("failed to send token to api, error:", e);
                onFailure.run();
                }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        //TODO show something more specific in case the token is just wrong, as the user might be alleviate themselves because they could have entered some bs.
                        log.info("The token endpoint response was not successful. Response: {}", response.toString());
                        onFailure.run();
                        return;
                    }

                    TokenResponse tokenResponse = new Gson().fromJson(responseBody.string(), TokenResponse.class);
                    String jwt = tokenResponse.getAccess_token();
                    if (jwt == null) {
                        log.info("jwt received from token endpoint is null!");
                        onFailure.run();
                        return;
                    }

                    log.info("successfully authenticated with token and got jwt");
                    plugin.getDataHandler().getAccountWideData().setToken(token);
                    plugin.getDataHandler().getAccountWideData().setJwt(jwt);
                    successfullyLoggedIn = true;
                    onSuccess.run();
                    alwaysRunOnLogin.forEach(Runnable::run);
                }
                catch (Exception e) {
                    log.info("unable to handle response from token endpoint error:", e);
                    onFailure.run();
                }
            }
        });
    }
}
