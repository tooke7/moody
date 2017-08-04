package com.jacobobryant.moody;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;

import com.jacobobryant.moody.vanilla.BuildConfig;
import com.jacobobryant.moody.vanilla.Song;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    public boolean next_on_blacklist = false;

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

        // read local songs
        final String[] proj = {MediaStore.Audio.Media.TITLE,
                               MediaStore.Audio.Media.ARTIST,
                               MediaStore.Audio.Media.ALBUM,
        };
        Cursor result = context.getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, null, null, null);
        songs = new ArrayList<>();
        result.moveToPosition(-1);
        while (result.moveToNext()) {
            String title = result.getString(0);
            String artist = result.getString(1);
            String album = result.getString(2);
            Metadata m = new Metadata(artist, album, title);
            m.source = "local";
            songs.add(m);
        }
        add_to_library(context, songs);
        result.close();

        // get library
        SQLiteDatabase db = new Database(context).getReadableDatabase();
        result = db.rawQuery("SELECT * FROM songs", null);
        rec = new reco(cursor_to_maps(result));
        result.close();

        // read in past skip data
        result = db.rawQuery("SELECT artist, album, title, skipped, time " +
                "FROM songs JOIN events ON songs._id = events.song_id " +
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
    }

    public void update(Song last_song, boolean skipped) {
        if (next_on_blacklist) {
            //rec.add_to_blacklist(new Metadata(last_song).toMap());
            next_on_blacklist = false;
            return;
        }

        // get the current algorithm. 0 means random.
        int algorithm;
        if (random_song != null && random_song.same_as(new Metadata(last_song.artist,
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
        Metadata m = new Metadata(last_song);
        Cursor result = db.rawQuery("SELECT _id FROM songs WHERE "
                + match_clause(m), m.query());
        if (result.getCount() > 0) {
            result.moveToPosition(0);
            int id = result.getInt(0);
            db.execSQL("INSERT INTO events (song_id, skipped, algorithm) VALUES (?, ?, ?)",
                    new String[]{String.valueOf(id), String.valueOf(skipped ? 1 : 0),
                    String.valueOf(algorithm)});
        } else {
            Log.e(C.TAG, "couldn't add song event");
        }
        result.close();
        db.close();
    }

    public Metadata pick_next(boolean local_only) {
        float CONTROL_PROB = 0.2f;

        Map ret;
        if (Math.random() < CONTROL_PROB) {
            // suggest a random song every now and then for evaluation purposes.
            ret = rec.pick_random(local_only);
        } else {
            ret = rec.pick_next(local_only);
        }

        if (ret == null) {
            return null;
        } else {
            return new Metadata(ret);
        }
    }

    public static void add_to_library(Context c, List<Metadata> songs) {
        SQLiteDatabase db = new Database(c).getWritableDatabase();
        db.beginTransaction();
        for (Metadata s : songs) {
            String query = "select count(*) from songs where " + match_clause(s);
            Cursor result = db.rawQuery(query, s.query());

            result.moveToFirst();
            long count = result.getLong(0);
            if (count == 0) {
                db.execSQL("INSERT INTO songs (artist, album, title, duration, source, spotify_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                        new String[] {s.artist, s.album, s.title, s.duration, s.source, s.spotify_id});
            }
        }
        db.setTransactionSuccessful();
        db.endTransaction();
        db.close();
    }

    private static String match_clause(Metadata s) {
        StringBuilder clause = new StringBuilder();
        if (s.artist != null) {
            clause.append("artist=?");
        } else {
            clause.append("artist is null");
        }
        if (s.album != null) {
            clause.append(" and album=?");
        } else {
            clause.append(" and album is null");
        }
        if (s.title != null) {
            clause.append(" and title=?");
        } else {
            clause.append(" and title is null");
        }
        return clause.toString();
    }


    public static cursor_to_maps(Cursor c) {
        List<Map<String, Object>> list = new ArrayList<>();
        int count = c.getColumnCount();
        c.moveToPosition(-1);
        while (result.moveToNext()) {
            Map<String, Object> m = new HashMap<>();
            for (int i = 0; i < count; i++) {
                String key = c.getColumnName(i);
                Object value;
                switch (c.getType(i)) {
                    case FIELD_TYPE_INTEGER:
                        value = c.getInt(i);
                        break;
                    case FIELD_TYPE_FLOAT:
                        value = c.getFloat(i);
                        break;
                    case FIELD_TYPE_STRING:
                        value = c.getString(i);
                        break;
                    case FIELD_TYPE_BLOB:
                        value = c.getBlob(i);
                        break;
                }
                m.put(key, value);
            }
            list.add(m);
        }
        return list;
    }
}
