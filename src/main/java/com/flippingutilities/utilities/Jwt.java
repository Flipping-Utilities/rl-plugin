package com.flippingutilities.utilities;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Value;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

@AllArgsConstructor
@Data
public class Jwt {
    JwtHeader header;
    JwtPayload payload;
    String signature;

    public static Jwt fromString(String jwtString, Gson gson) throws JsonSyntaxException, ArrayIndexOutOfBoundsException, IllegalArgumentException {
        //need to escape the "." as it's a special character
        String[] parts = jwtString.split("\\.");
        String jwtHeaderString = new String(Base64.getDecoder().decode(parts[0]));
        String jwtPayloadString = new String(Base64.getDecoder().decode(parts[1]));
        JwtHeader jwtHeader = gson.fromJson(jwtHeaderString, JwtHeader.class);
        JwtPayload jwtPayload = gson.fromJson(jwtPayloadString, JwtPayload.class);
        String signature = parts[2];
        return new Jwt(jwtHeader, jwtPayload, signature);
    }

    public boolean isExpired() {
        return this.payload.exp <=  Instant.now().getEpochSecond();
    }

    /**
     * JWT should be refreshed if it will expire within the next 10 days. 10 days is a pretty safe buffer as we
     * run this check on client start up, and RL updates every week, so players' should not have their client open
     * for more than 10 days.
     */
    public boolean shouldRefresh() {
        return this.payload.exp <= Instant.now().plus(10, ChronoUnit.DAYS).getEpochSecond();
    }
}

@Data
class JwtHeader {
    String alg;
    String typ;
}

@Data
class JwtPayload {
    String userId;
    String discordId;
    int iat;
    long exp; //epoch seconds
}