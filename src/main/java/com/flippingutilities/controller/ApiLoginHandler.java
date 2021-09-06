package com.flippingutilities.controller;

import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * This class handles all of the extra logic associated with performing logins.
 * ALL attempts to login with the api MUST go through this class.
 * Its main responsibility is ensuring that there is a central place where  components can subscribe to successful
 * logins and fire off some action whenever a login happens.
 */
@Slf4j
public class ApiLoginHandler {
    FlippingPlugin plugin;
    public boolean successfullyLoggedIn;
    List<Runnable> loginSubscriberActions = new ArrayList<>();

    public ApiLoginHandler(FlippingPlugin plugin) {
        this.plugin = plugin;
    }

    public void subscribeToLogin(Runnable r) {
        this.loginSubscriberActions.add(r);
    }

    //used to authenticate with the api when the client first opens (as opposed to the authentication attempt when
    //a user clicks the login button on the login panel).
    public void login() {
        String token = plugin.getDataHandler().getAccountWideData().getToken();
        if (token == null) {
            log.info("no token stored locally, not attempting to login");
            return;
        }
        login(token);
    }

    public CompletableFuture<String> login(String token) {
        log.info("attempting to login!");
        CompletableFuture<String> jwtFuture = plugin.getApiRequestHandler().loginWithToken(token);

        jwtFuture.whenComplete((jwt, exception) -> {
            if (exception != null) {
                log.info("failed to login!", exception);
            }
            else {
                plugin.getDataHandler().getAccountWideData().setToken(token);
                plugin.getDataHandler().getAccountWideData().setJwt(jwt);
                successfullyLoggedIn = true;
                loginSubscriberActions.forEach(Runnable::run);
            }
        });

        return jwtFuture;
    }
}
