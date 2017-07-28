package com.jacobobryant.moody;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;

import com.jacobobryant.moody.vanilla.BuildConfig;
import com.jacobobryant.moody.vanilla.PlaybackService;
import com.jacobobryant.moody.vanilla.PrefKeys;
import com.jacobobryant.moody.vanilla.Song;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import reco.reco;

public class Moody {
    private static Moody instance;
    private Context context;
    private List<Metadata> songs;
    private Metadata random_song;
    public static final String AUTHORITY = "com.jacobobryant.moody.vanilla";
    public static final String ACCOUNT_TYPE = "com.jacobobryant";
    public static final String ACCOUNT = "moodyaccount";
    private Account newAccount;
    public reco rec;

    private String spotify_token;
    private static final int REQUEST_CODE = 666;


    private Moody() { }

    public static Moody getInstance(Context context) {
        if (instance == null) {
            Log.d(C.TAG, "package name:" + context.getPackageName());
            instance = new Moody();
            instance.context = context;
            instance.init();
        }
        return instance;
    }

    private void init() {
        Log.d(C.TAG, "Moody.init()");

        // setup sync adapter
        final long SYNC_INTERVAL = 60L * 60L * 24L;
        newAccount = new Account(ACCOUNT, ACCOUNT_TYPE);
        AccountManager accountManager = (AccountManager) context.getSystemService(Context.ACCOUNT_SERVICE);
        if (accountManager.addAccountExplicitly(newAccount, null, null)) {
            if (BuildConfig.DEBUG) Log.d(C.TAG, "creating new account");
            ContentResolver.setIsSyncable(newAccount, AUTHORITY, 1);
            ContentResolver.setSyncAutomatically(newAccount, AUTHORITY, true);
        }
        Log.d(C.TAG, "setting periodic sync: " + SYNC_INTERVAL);
        ContentResolver.addPeriodicSync(newAccount, AUTHORITY, Bundle.EMPTY, SYNC_INTERVAL);

        // read in old data
        final String[] proj = {MediaStore.Audio.Media.TITLE,
                               MediaStore.Audio.Media.ARTIST,
                               MediaStore.Audio.Media.ALBUM,
                               MediaStore.Audio.Media.DURATION,
        };

        Cursor result = context.getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, null, null, null);
        SQLiteDatabase db = new Database(context).getWritableDatabase();
        db.beginTransaction();

        songs = new ArrayList<>();
        // get metadata for all the user's songs
        result.moveToPosition(-1);
        while (result.moveToNext()) {
            String title = result.getString(0);
            String artist = result.getString(1);
            String album = result.getString(2);

            // TODO change ON CONFLICT thing to UPDATE?
            db.execSQL("INSERT INTO songs (artist, album, title) VALUES (?, ?, ?)",
                    new String[] {artist, album, title});

            songs.add(new Metadata(artist, album, title));
        }

        db.setTransactionSuccessful();
        db.endTransaction();
        result.close();

        // get spotify songs
        //db.beginTransaction();
        SharedPreferences settings = PlaybackService.getSettings(context);
        String token = settings.getString(PrefKeys.SPOTIFY_TOKEN, null);
        try {
            // query spotify
            URL obj;
            try {
                obj = new URL("https://api.spotify.com/v1/me/top/tracks?limit=50");
            } catch (MalformedURLException e) {
                // it's not malformed, I just effin hardcoded it! :(
                throw new RuntimeException("go kill yourself");
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

            for (Map<String, String> song : (List<Map<String, String>>)
                    reco.parse_spotify_response(response.toString())) {
                String uri = song.get("uri");
                String artist = song.get("artist");

                //db.execSQL("INSERT INTO songs (artist, title) " +
                //        "VALUES (?, ?, ?)", new String[] {artist, uri});
                //songs.add(new Metadata(artist, album, title));
                Log.d(C.TAG, "adding spotify uri " + uri + " by " + artist);
            }
        } catch (IOException e) {
            Log.e(C.TAG, "io problem with spotify: ", e);
            e.printStackTrace();
        }
        //db.setTransactionSuccessful();
        //db.endTransaction();
        Log.d(C.TAG, "finished spotify thang");

        rec = new reco(conv(songs));

        // read in past skip data
        result = db.rawQuery("SELECT artist, album, title, skipped, time " +
                "FROM songs JOIN events ON songs._id = events.song_id " +
                //"WHERE algorithm != 0", null);
                "ORDER BY time ASC", null);
        result.moveToPosition(-1);
        while (result.moveToNext()) {
            String artist = result.getString(0);
            String album = result.getString(1);
            String title = result.getString(2);
            boolean skipped = (result.getInt(3) == 1);
            String time = result.getString(4);

            try {
                long seconds = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .parse(time).getTime() / 1000;

                rec.add_event(artist, album, title, skipped, seconds);
            } catch (ParseException e) {
                Log.e(C.TAG, "date couldn't be parsed");
            }
        }
        result.close();
        db.close();

        Log.d(C.TAG, "updating model");
        rec.update_model();
        Log.d(C.TAG, "finished updating model");
    }

    public void update(Song last_song, boolean skipped) {
        // get the current algorithm. 0 means random.
        int algorithm;
        if (random_song != null && random_song.equals(new Metadata(last_song.artist,
                    last_song.album, last_song.title))) {
            algorithm = 0;
            random_song = null;
        } else {
            algorithm = C.ALG_VERSION;
        }
        rec.add_event(last_song.artist, last_song.album,
                last_song.title, skipped);

        // update db
        SQLiteDatabase db = new Database(context).getWritableDatabase();
        Cursor result = db.rawQuery(
                "SELECT _id FROM songs WHERE artist = ? AND album = ? AND title = ?",
                new String[] {last_song.artist, last_song.album, last_song.title});
        int id;
        if (result.getCount() > 0) {
            result.moveToPosition(0);
            id = result.getInt(0);
        } else {
            db.beginTransaction();
            db.execSQL("INSERT INTO songs (artist, album, title) " +
                       "VALUES (?, ?, ?)",
                    new String[] {last_song.artist, last_song.album, last_song.title});
            Cursor idCursor = db.rawQuery("SELECT last_insert_rowid()", null);
            idCursor.moveToPosition(0);
            id = idCursor.getInt(0);
            idCursor.close();
            db.setTransactionSuccessful();
            db.endTransaction();
        }
        db.execSQL("INSERT INTO events (song_id, skipped, algorithm) VALUES (?, ?, ?)",
                new String[]{String.valueOf(id), String.valueOf(skipped ? 1 : 0),
                String.valueOf(algorithm)});
        result.close();
        db.close();
    }

    public Metadata pick_next() {
        float CONTROL_PROB = 0.2f;

        // suggest a random song every now and then for evaluation purposes.
        if (Math.random() < CONTROL_PROB) {
            int item = new Random().nextInt(songs.size());
            random_song = songs.get(item);
            Log.d(C.TAG, "suggesting random song: " + random_song);
            return random_song;
        } else {
          return new Metadata(rec.pick_next());
        }
    }

    private List<Map<String, String>> conv(List<Metadata> songs) {
        List<Map<String, String>> ret = new ArrayList<>();
        for (Metadata song : songs) {
            ret.add(song.toMap());
        }
        return ret;
    }
}
