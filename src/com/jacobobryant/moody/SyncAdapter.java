package com.jacobobryant.moody;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.SyncResult;
import android.os.Bundle;
import android.util.Log;

import com.jacobobryant.moody.C;

import java.util.Map;

import javax.net.ssl.SSLContext;

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
        } catch (Exception e) {
            //ACRA.getErrorReporter().handleException(e);
        }
    }


    //String upload(String json) throws IOException {
    //    URL url;
    //    try {
    //        url = new URL(C.SERVER + "/upload");
    //    } catch (MalformedURLException e) {
    //        throw new RuntimeException(e);
    //    }

    //    HttpsURLConnection conn = makeConnection(url);

    //    // set up request
    //    conn.setRequestProperty("Content-Type", "application/json");
    //    conn.setRequestProperty("Connection", "close");
    //    conn.setRequestMethod("POST");
    //    conn.setDoOutput(true);
    //    conn.setDoInput(true);

    //    // send request
    //    OutputStream os = conn.getOutputStream();
    //    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
    //    writer.write(json);
    //    writer.flush();
    //    writer.close();
    //    os.close();

    //    // receive response
    //    StringBuilder result = new StringBuilder();
    //    BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
    //    String line;
    //    while ((line = rd.readLine()) != null) {
    //        result.append(line);
    //    }
    //    rd.close();

    //    return result.toString();
    //}

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
