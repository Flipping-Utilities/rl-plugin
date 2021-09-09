package com.flippingutilities.controller;

import com.flippingutilities.utilities.Jwt;
import com.flippingutilities.utilities.OsrsAccount;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * This class handles all of the extra logic associated with performing logins.
 * ALL attempts to loginWithToken with the api MUST go through this class.
 * Its main responsibility is ensuring that there is a central place where  components can subscribe to successful
 * logins and fire off some action whenever a loginWithToken happens.
 */
@Slf4j
public class authHandler {
    FlippingPlugin plugin;
    private boolean validJwt;
    private Set<String> successfullyRegisteredRsns = new HashSet<>();
    List<Runnable> loginSubscriberActions = new ArrayList<>();

    public authHandler(FlippingPlugin plugin) {
        this.plugin = plugin;
    }

    public void subscribeToLogin(Runnable r) {
        this.loginSubscriberActions.add(r);
    }

    /**
     * Shouldn't bother sending requests to the api if the jwt is not valid or the rsn was not successfully registered.
     * This is purely to prevent the plugin from sending unnecessary requests, the api would reject the requests anyway.
     */
    public boolean canCommunicateWithApi(String displayName) {
        return this.validJwt && successfullyRegisteredRsns.contains(displayName);
    }

    //used to authenticate with the api when the client first opens (as opposed to the authentication attempt when
    //a user clicks the loginWithToken button on the loginWithToken panel).
    public void checkExistingJwt() {
        String jwtString = plugin.getDataHandler().getAccountWideData().getJwt();
        if (jwtString == null) {
            log.info("no jwt stored locally, not attempting to check existing jwt");
            return;
        }
        try {
            Jwt jwt = Jwt.fromString(jwtString);
            if (jwt.isExpired()) {
                CompletableFuture<String> newJwtFuture = plugin.getApiRequestHandler().refreshJwt(jwtString);
                newJwtFuture.whenComplete((newJwt, exception) -> {
                    if (exception != null) {
                        log.info("failed to refresh jwt, error: ", exception);
                    }
                    else {
                        plugin.getDataHandler().getAccountWideData().setJwt(newJwt);
                        validJwt = true;
                        loginSubscriberActions.forEach(Runnable::run);
                    }
                });
            }
        }
        //this catch clause is just for the Jwt.fromString line
        catch (Exception e) {
            log.info("failed to check existing jwt error: ", e);
        }
    }

    public void checkRsn(String displayName) {
        CompletableFuture<List<OsrsAccount>> userAccountsFuture = plugin.getApiRequestHandler().getUserAccounts();
        userAccountsFuture.thenApply(accs -> {
            Set<String> registeredRsns = accs.stream().map(OsrsAccount::getRsn).collect(Collectors.toSet());
            if (registeredRsns.contains(displayName)) {
                successfullyRegisteredRsns.add(displayName);
                log.info("rsn: {} is already registered, not registering again", displayName);
            }
            else {
                plugin.getApiRequestHandler().registerNewAccount(displayName).
                        thenApply(acc -> successfullyRegisteredRsns.add(acc.getRsn())).
                        exceptionally(e -> {
                            log.info("could not register display name: {}, error: {}", displayName, e);
                            return null;
                });
            }
            return null;
        }).exceptionally(e -> {
            log.info("could not check rsn", e);
            return null;
        });
    }

    public CompletableFuture<String> loginWithToken(String token) {
        log.info("attempting to login with token!");
        CompletableFuture<String> jwtFuture = plugin.getApiRequestHandler().loginWithToken(token);

        jwtFuture.whenComplete((jwt, exception) -> {
            if (exception != null) {
                log.info("failed to loginWithToken!", exception);
            }
            else {
                plugin.getDataHandler().getAccountWideData().setJwt(jwt);
                validJwt = true;
                loginSubscriberActions.forEach(Runnable::run);
                if (plugin.getCurrentlyLoggedInAccount() != null) {
                    checkRsn(plugin.getCurrentlyLoggedInAccount());
                }
            }
        });

        return jwtFuture;
    }
}
