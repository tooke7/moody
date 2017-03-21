package com.jacobobryant.moody;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SyncResult;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.UUID;
import org.apache.commons.io.FileUtils;

import com.jacobobryant.moody.vanilla.PrefKeys;

public class SyncAdapter extends AbstractThreadedSyncAdapter {
    Context context;
    //SSLContext sslContext;

    public SyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
        this.context = context;
        init();
    }

    public SyncAdapter(Context context, boolean autoInitialize,
            boolean allowParallelSyncs) {
        super(context, autoInitialize, allowParallelSyncs);
        this.context = context;
        init();
    }

    void init() {
        //try {
        //    this.sslContext = makeContext();
        //} catch (CertificateException | IOException | KeyStoreException |
        //        NoSuchAlgorithmException | KeyManagementException |
        //        NoSuchProviderException e) {
        //    throw new RuntimeException("", e);
        //}
    }

    @Override
    public void onPerformSync(Account account, Bundle extras, String authority,
                              ContentProviderClient provider, SyncResult syncResult) {
        try {
            Log.d(C.TAG, "onPerformSync()");
            sync(context);
        } catch (Exception e) {
            //ACRA.getErrorReporter().handleException(e);
        }
    }

    public static void sync(Context context) {
        try {
            URL url;
            try {
                url = new URL(C.SERVER + "/upload/" + getUserId(context));
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            // set up request
            conn.setRequestProperty("Content-Type", "binary/octet-stream");
            conn.setRequestProperty("Connection", "close");
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setDoInput(true);

            // send request
            BufferedOutputStream os = new BufferedOutputStream(conn.getOutputStream());
            File db = new File(context.getDatabasePath("moody.db").getPath());
            byte[] data = FileUtils.readFileToByteArray(db);
            Log.d(C.TAG, "data.length: " + data.length);
            os.write(data);
            os.close();

            // receive response
            StringBuilder result = new StringBuilder();
            BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String line;
            while ((line = rd.readLine()) != null) {
                result.append(line);
            }
            rd.close();

            Log.d(C.TAG, "finished sending data");
        } catch (IOException e) {
            Log.e(C.TAG, e.getMessage());
        }
    }

    private static String getUserId(Context context) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        String userId = settings.getString(PrefKeys.USER_ID, "");
        if (!userId.isEmpty()) {
            return userId;
        }
        userId = UUID.randomUUID().toString();
        SharedPreferences.Editor editor = settings.edit();
        editor.putString(PrefKeys.USER_ID, userId);
        editor.commit();
        return userId;
    }

    //SSLContext makeContext() throws CertificateException, IOException, KeyStoreException,
    //        NoSuchAlgorithmException, KeyManagementException, NoSuchProviderException {
    //    // Load CAs from an InputStream
    //    CertificateFactory cf = CertificateFactory.getInstance("X.509");
    //    InputStream caInput = new BufferedInputStream(
    //            this.context.getAssets().open("jacobobryant.com.crt"));

    //    Certificate ca;
    //    try {
    //        ca = cf.generateCertificate(caInput);
    //    } finally {
    //        caInput.close();
    //    }

    //    // Create a KeyStore containing our trusted CAs
    //    String keyStoreType = KeyStore.getDefaultType();
    //    KeyStore keyStore = KeyStore.getInstance(keyStoreType);
    //    keyStore.load(null, null);
    //    keyStore.setCertificateEntry("ca", ca);

    //    // Create a TrustManager that trusts the CAs in our KeyStore
    //    String tmfAlgorithm = TrustManagerFactory.getDefaultAlgorithm();
    //    TrustManagerFactory tmf = TrustManagerFactory.getInstance(tmfAlgorithm);
    //    tmf.init(keyStore);

    //    // Create an SSLContext that uses our TrustManager
    //    SSLContext context = SSLContext.getInstance("TLS");
    //    context.init(null, tmf.getTrustManagers(), null);
    //    return context;
    //}

    //HttpsURLConnection makeConnection(URL url) throws IOException {
    //    HostnameVerifier hostnameVerifier = new HostnameVerifier() {
    //        @Override
    //        public boolean verify(String hostname, SSLSession session) {
    //            // hee hee hee
    //            return true;
    //        }
    //    };

    //    HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
    //    urlConnection.setSSLSocketFactory(this.sslContext.getSocketFactory());
    //    urlConnection.setHostnameVerifier(hostnameVerifier);
    //    return urlConnection;
    //}
}
