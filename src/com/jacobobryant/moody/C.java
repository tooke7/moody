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
    public static final int ALG_VERSION = 3;
}
