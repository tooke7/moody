package com.jacobobryant.moody;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

public class C {
    public static final String TAG = "MoodyMusic";
    public static final String SERVER = "http://jacobobryant.com:5666";
    public static final int ALG_VERSION = 1;

    public static String post(String endpoint, String json) throws IOException {
        URL url;
        try {
            url = new URL(C.SERVER + endpoint);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        // set up request
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Connection", "close");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setDoInput(true);

        // send request
        OutputStream os = conn.getOutputStream();
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
        writer.write(json);
        writer.flush();
        writer.close();
        os.close();

        // receive response
        StringBuilder result = new StringBuilder();
        BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String line;
        while ((line = rd.readLine()) != null) {
            result.append(line);
        }
        rd.close();

        return result.toString();
    }
}
