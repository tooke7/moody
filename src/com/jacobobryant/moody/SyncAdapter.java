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

import com.jacobobryant.moody.vanilla.PlaybackService;
import com.jacobobryant.moody.vanilla.PrefKeys;

import org.apache.commons.io.FileUtils;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import reco.reco;

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
        Log.d(C.TAG, "SyncAdapter.init()");
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
            syncDb(context);
            getSpotifySongs(context);
        } catch (Exception e) {
            //ACRA.getErrorReporter().handleException(e);
            Log.e(C.TAG, "couldn't upload db", e);
            e.printStackTrace();
        }
    }

    public static void syncDb(Context context) {
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

    public void getSpotifySongs(Context context) throws IOException {
        // get spotify songs
        SharedPreferences settings = PlaybackService.getSettings(context);
        String token = settings.getString(PrefKeys.SPOTIFY_TOKEN, null);
        if (token == null) {
            return;
        }

        // query spotify
        URL obj;
        try {
            obj = new URL("https://api.spotify.com/v1/me/top/tracks?limit=50");
        } catch (MalformedURLException e) {
            throw new RuntimeException();
        }
        HttpURLConnection con = (HttpURLConnection) obj.openConnection();
        con.setRequestMethod("GET");
        con.setRequestProperty("Authorization", "Bearer " + token);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(con.getInputStream()));
        String inputLine;
        StringBuffer response = new StringBuffer();

        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();

        List<Metadata> songs = new LinkedList<>();
        for (Map<String, String> song : (List<Map<String, String>>)
                reco.parse_top_tracks(response.toString())) {
            String uri = song.get("uri");
            String artist = song.get("artist");
            Log.d(C.TAG, "adding spotify uri " + uri + " by " + artist);
            song.put("source", "spotify");
            songs.add(new Metadata(song));
        }
        Moody.add_to_library(context, songs);
        Log.d(C.TAG, "finished spotify thang");
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
}
