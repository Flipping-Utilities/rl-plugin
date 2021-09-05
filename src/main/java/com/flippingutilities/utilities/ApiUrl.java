package com.flippingutilities.utilities;

public class ApiUrl {

    public static String getBaseUrl() {
        if (System.getenv("apiurl") == null) {
            //TODO replace with actual url of course
            return "https://discord.com/login/";
        }
        else {
            return System.getenv("apiurl");
        }
    }

    public static String getTokenUrl() {
        return getBaseUrl() + "auth/token";
    }


}
