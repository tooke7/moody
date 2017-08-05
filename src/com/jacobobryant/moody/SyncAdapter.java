package com.jacobobryant.moody;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SyncResult;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;
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
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;

import reco.reco;

import static android.os.AsyncTask.THREAD_POOL_EXECUTOR;

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
            getSpotifyIds(context);
            //getSpotifyFeatures(context);
        } catch (Exception e) {
            //ACRA.getErrorReporter().handleException(e);
            Log.e(C.TAG, "couldn't finish sync stuff", e);
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
        if (Moody.spotify_token_expired(context)) {
            return;
        }
        SharedPreferences settings = PlaybackService.getSettings(context);
        String token = settings.getString(PrefKeys.SPOTIFY_TOKEN, null);

        // query spotify
        URL obj;
        try {
            obj = new URL("https://api.spotify.com/v1/me/top/tracks?limit=50");
        } catch (MalformedURLException e) {
            throw new RuntimeException();
        }
        String response = query_spotify(obj, token);

        List<Metadata> songs = new LinkedList<>();
        for (Map<String, Object> song : (List<Map<String, Object>>)
                reco.parse_top_tracks(response)) {
            Metadata m = new Metadata(song);
            m.source = "spotify";
            songs.add(m);
        }
        Moody.add_to_library(context, songs);
        Log.d(C.TAG, "finished spotify thang");
    }

    public void getSpotifyIds(Context context) throws IOException {
        if (Moody.spotify_token_expired(context)) {
            return;
        }
        SharedPreferences settings = PlaybackService.getSettings(context);
        String token = settings.getString(PrefKeys.SPOTIFY_TOKEN, null);

        boolean exit = false;
        whileLoop:
        while (!exit) {

            final int LIMIT = 20;
            SQLiteDatabase db = new Database(context).getReadableDatabase();
            Cursor curs = db.rawQuery("select _id, artist, album, title " +
                            "from songs where spotify_id is null limit " +
                            LIMIT, null);
            List<Map<String, Object>> result = Moody.cursor_to_maps(curs);
            curs.close();
            db.close();
            int size = result.size();
            exit = size < LIMIT;
            Log.d(C.TAG, "got " + size + " songs without spotify ids");
            CountDownLatch latch = new CountDownLatch(size);

            for (Map<String, Object> row : result) {
                URL url;
                int row_id = (int) row.get("_id");
                String artist = (String) row.get("artist");
                String album = (String) row.get("album");
                String title = (String) row.get("title");

                try {
                    url = new URL(get_search_query(artist, album, title));
                    new GetIdTask(url, token, row_id, context, latch)
                            .executeOnExecutor(THREAD_POOL_EXECUTOR);
                } catch (MalformedURLException e) {
                    Log.e(C.TAG, "couldn't get spotify id for " + title +
                            " by " + artist + " (url encoding error):", e);
                    db = new Database(context).getWritableDatabase();
                    db.execSQL("update songs set spotify_id = ? where _id = ?",
                            new String[] {"MalformedURLException",
                                String.valueOf(row_id)});
                    db.close();
                    latch.countDown();
                } catch (RejectedExecutionException e) {
                    Log.e(C.TAG, "GetIdTask rejected, waiting 5 seconds before trying again...");
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ex) { }
                    continue whileLoop;
                }
            }
            try {
                latch.await();
                Log.d(C.TAG, "waiting 5 seconds before starting next batch...");
                Thread.sleep(5000);
            } catch (InterruptedException e) { }
        }
        Log.d(C.TAG, "finished getSpotifyIds()");
    }

    private String get_search_query(String artist, String album, String title)
            throws UnsupportedEncodingException, MalformedURLException {
        StringBuilder query = new StringBuilder();
        query.append("https://api.spotify.com/v1/search?type=track&q=");
        if (artist != null) {
            query.append("artist:");
            query.append(URLEncoder.encode(artist, "UTF-8"));
            query.append("%20");
        }
        if (title != null) {
            query.append("track:" + URLEncoder.encode(title, "UTF-8"));
        } else {
            throw new MalformedURLException("no track");
        }
        return query.toString();
    }

    private class GetIdTask extends AsyncTask<Void, Void, Void> {
        private URL url;
        private String token;
        private int row_id;
        private Context context;
        private CountDownLatch latch;

        public GetIdTask(URL url, String token, int row_id,
                Context context, CountDownLatch latch) {
            this.url = url;
            this.token = token;
            this.row_id = row_id;
            this.context = context;
            this.latch = latch;
        }

        @Override
        protected Void doInBackground(Void... v) {
            try {
                String spotify_id = reco.parse_search(query_spotify(url, token));
                if (spotify_id == null) {
                    spotify_id = "none";
                }
                Log.d(C.TAG, "got spotify song id: " + spotify_id);
                SQLiteDatabase db = new Database(context).getWritableDatabase();
                db.execSQL("update songs set spotify_id = ? where _id = ?",
                        new String[]{spotify_id, String.valueOf(row_id)});
                db.close();
            } catch (Exception e) {
                Log.e(C.TAG, "couldn't get spotify_id for url " + url + ":", e);
            }
            latch.countDown();
            return null;
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

    private static String query_spotify(URL url, String token) throws IOException {
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
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
        return response.toString();
    }
}
