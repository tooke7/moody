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
                               MediaStore.Audio.Media.DURATION,
        };
        Cursor result = context.getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, null, null, null);
        songs = new ArrayList<>();
        result.moveToPosition(-1);
        while (result.moveToNext()) {
            String title = result.getString(0);
            String artist = result.getString(1);
            String album = result.getString(2);
            songs.add(new Metadata(artist, album, title));
        }
        add_to_library(context, songs);
        result.close();

        // get library
        SQLiteDatabase db = new Database(context).getReadableDatabase();
        List<Map<String, String>> library = new ArrayList<>();
        result = db.rawQuery("SELECT artist, album, title FROM songs", null);
        result.moveToPosition(-1);
        while (result.moveToNext()) {
            String artist = result.getString(0);
            String album  = result.getString(1);
            String title  = result.getString(2);
            library.add(new Metadata(artist, album, title).toMap());
        }
        result.close();
        rec = new reco(library);

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

    public void update(Song last, boolean skipped) {
        Song last_song = new Song(-1);
        last_song.title = last.title;
        last_song.album = last.album;
        last_song.artist = last.artist;
        last_song.path = last.path;

        if (last_song.path.contains("spotify:track:")) {
            last_song.title = last_song.path;
            last_song.album = null;
        }

        if (next_on_blacklist) {
            //rec.add_to_blacklist(new Metadata(last_song).toMap());
            next_on_blacklist = false;
            return;
        }

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
        Metadata m = new Metadata(last_song);
        Cursor result = db.rawQuery("SELECT _id FROM songs WHERE "
                + match_clause(m), m.query());
        int id;
        if (result.getCount() > 0) {
            result.moveToPosition(0);
            id = result.getInt(0);
        } else {
            db.execSQL("INSERT INTO songs (artist, album, title) " +
                       "VALUES (?, ?, ?)",
                    new String[] {last_song.artist, last_song.album, last_song.title});
            Cursor idCursor = db.rawQuery("SELECT last_insert_rowid()", null);
            idCursor.moveToPosition(0);
            id = idCursor.getInt(0);
            idCursor.close();
        }
        db.execSQL("INSERT INTO events (song_id, skipped, algorithm) VALUES (?, ?, ?)",
                new String[]{String.valueOf(id), String.valueOf(skipped ? 1 : 0),
                String.valueOf(algorithm)});
        result.close();
        db.close();
    }

    public Metadata pick_next(boolean local_only) {
        float CONTROL_PROB = 0.2f;

        // suggest a random song every now and then for evaluation purposes.
        if (Math.random() < CONTROL_PROB) {
            List<Metadata> universe = new ArrayList<>();
            for (Metadata song : songs) {
                if (!local_only || !song.title.startsWith("spotify:track:")) {
                    universe.add(song);
                }
            }
            if (universe.size() > 0) {
                int item = new Random().nextInt(universe.size());
                random_song = universe.get(item);
                Log.d(C.TAG, "suggesting random song: " + random_song);
                return random_song;
            } else {
                return null;
            }
        } else {
            Map ret = rec.pick_next(local_only);
            if (ret == null) {
                return null;
            } else {
                return new Metadata(ret);
            }
        }
    }

    private List<Map<String, String>> conv(List<Metadata> songs) {
        List<Map<String, String>> ret = new ArrayList<>();
        for (Metadata song : songs) {
            ret.add(song.toMap());
        }
        return ret;
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
                db.execSQL("INSERT INTO songs (artist, album, title) " +
                        "VALUES (?, ?, ?)",
                        new String[] {s.artist, s.album, s.title});
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
}
