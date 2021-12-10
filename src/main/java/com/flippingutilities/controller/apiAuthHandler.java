package com.flippingutilities.controller;

import com.flippingutilities.utilities.Jwt;
import com.flippingutilities.utilities.OsrsAccount;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * This class handles all of the extra logic associated with performing logins.
 * ALL attempts to login with the api MUST go through this class.
 * Its main responsibility is ensuring that there is a central place where  components can subscribe to successful
 * logins and fire off some action whenever a login happens.
 */
@Slf4j
public class apiAuthHandler {
    FlippingPlugin plugin;
    @Getter
    private boolean hasValidJWT;
    private Set<String> successfullyRegisteredRsns = new HashSet<>();
    List<Runnable> loginSubscriberActions = new ArrayList<>();

    public apiAuthHandler(FlippingPlugin plugin) {
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
        return this.hasValidJWT && successfullyRegisteredRsns.contains(displayName);
    }

    /**
     * Checks if the existing JWT has expired, and if so, gets a refreshed JWT. The setting of validJwt is purely
     * to stop the plugin from making useless requests, the api will safely reject invalid jwts itself.
     *
     * This should be called on client start up
     */
    public void checkExistingJwt() {
        String jwtString = plugin.getDataHandler().getAccountWideData().getJwt();
        if (jwtString == null) {
            log.info("no jwt stored locally, not attempting to check existing jwt");
            return;
        }
        try {
            Jwt jwt = Jwt.fromString(jwtString, plugin.gson);
            if (jwt.isExpired()) {
                //TODO use master panel to display message about having to relog using a new token from flopper
                log.info("jwt is expired, prompting user to re log");
                hasValidJWT = false;
                return;
            }
            if (jwt.shouldRefresh()) {
                log.info("jwt should refresh");
                hasValidJWT = true; //so it passes the check in refreshJwt
                CompletableFuture<String> newJwtFuture = plugin.getApiRequestHandler().refreshJwt(jwtString);
                newJwtFuture.whenComplete((newJwt, exception) -> {
                    if (exception != null) {
                        log.info("failed to refresh jwt, error: ", exception);
                        hasValidJWT = false; //validJwt is false by default, just setting it here for clarity
                    }
                    else {
                        plugin.getDataHandler().getAccountWideData().setJwt(newJwt);
                        hasValidJWT = true;
                        log.info("successfully refreshed jwt");
                        loginSubscriberActions.forEach(Runnable::run);
                    }
                });
            }
            else {
                log.info("jwt is valid");
                hasValidJWT = true;
                loginSubscriberActions.forEach(Runnable::run);
            }
        }
        //this catch clause is just for the Jwt.fromString line
        catch (Exception e) {
            log.info("failed to check existing jwt error: ", e);
        }
    }

    public CompletableFuture<Set<String>> checkRsn(String displayName) {
        if (!hasValidJWT) {
            log.debug("not checking rsn as we don't have a valid jwt yet");
            return CompletableFuture.completedFuture(this.successfullyRegisteredRsns);
        }
        CompletableFuture<List<OsrsAccount>> userAccountsFuture = plugin.getApiRequestHandler().getUserAccounts();
        return userAccountsFuture.thenCompose(accs -> {
            Set<String> registeredRsns = accs.stream().map(OsrsAccount::getRsn).collect(Collectors.toSet());
            if (registeredRsns.contains(displayName)) {
                successfullyRegisteredRsns.add(displayName);
                log.debug("rsn: {} is already registered, not registering again", displayName);
                return CompletableFuture.completedFuture(successfullyRegisteredRsns);
            }
            else {
                return plugin.getApiRequestHandler().registerNewAccount(displayName).
                        thenApply(acc -> {
                            successfullyRegisteredRsns.add(acc.getRsn());
                            log.debug("added rsn: {} to successfullyRegisteredRsns", acc.getRsn());
                            return successfullyRegisteredRsns;
                        }).
                        exceptionally(e -> {
                            log.debug("could not register display name: {}, error: {}", displayName, e);
                            return successfullyRegisteredRsns;
                });
            }
        }).exceptionally(e -> {
            log.debug("could not check rsn", e);
            return successfullyRegisteredRsns;
        });
    }

    public CompletableFuture<String> loginWithToken(String token) {
        log.info("attempting to login with token!");
        CompletableFuture<String> jwtFuture = plugin.getApiRequestHandler().loginWithToken(token);

        jwtFuture.whenComplete((jwt, exception) -> {
            if (exception != null) {
                log.info("failed to login with token!", exception);
            }
            else {
                plugin.getDataHandler().getAccountWideData().setJwt(jwt);
                hasValidJWT = true;
                loginSubscriberActions.forEach(Runnable::run);
                log.info("successfully logged in with token!");
                if (plugin.getCurrentlyLoggedInAccount() != null) {
                    checkRsn(plugin.getCurrentlyLoggedInAccount());
                }
            }
        });

        return jwtFuture;
    }
}
