package com.jacobobryant.moody;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;

import com.jacobobryant.moody.vanilla.BuildConfig;
import com.jacobobryant.moody.vanilla.PlaybackService;
import com.jacobobryant.moody.vanilla.PrefKeys;
import com.jacobobryant.moody.vanilla.Song;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import reco.reco;

import static android.database.Cursor.FIELD_TYPE_BLOB;
import static android.database.Cursor.FIELD_TYPE_FLOAT;
import static android.database.Cursor.FIELD_TYPE_INTEGER;
import static android.database.Cursor.FIELD_TYPE_STRING;

public class Moody {
    private static Moody instance;
    private Context context;
    public static final String AUTHORITY = "com.jacobobryant.moody.vanilla";
    public static final String ACCOUNT_TYPE = "com.jacobobryant";
    public static final String ACCOUNT = "moodyaccount";
    private Account newAccount;
    private reco rec;

    private Moody() { }

    public synchronized static Moody getInstance(Context context,
            InitProgressListener listener) {
        if (instance == null) {
            Log.d(C.TAG, "package name:" + context.getPackageName());
            instance = new Moody();
            instance.context = context;
            instance.init(listener);
        }
        return instance;
    }

    public synchronized static Moody getInstance(Context context) {
        return getInstance(context, new InitProgressListener() {
            @Override
            public void update(String s) { }
        });
    }

    private void init(InitProgressListener listener) {
        Log.d(C.TAG, "Moody.init()");

        // setup sync adapter
        listener.update("Setting up sync adapter");
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
        listener.update("reading local library");
        final String[] proj = {MediaStore.Audio.Media.TITLE,
                               MediaStore.Audio.Media.ARTIST,
                               MediaStore.Audio.Media.ALBUM,
        };
        Cursor result = context.getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, null, null, null);
        List<Metadata> songs = new ArrayList<>();
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

        SQLiteDatabase db = new Database(context).getWritableDatabase();
        // update all strengths
        //listener.update("updating memory strengths");
        //for (Map<String, Object> record : cursor_to_maps(db.rawQuery(
        //        "select distinct song_id from events", null))) {
        //    update_strength((int)record.get("song_id"), db);
        //}

        // get library
        listener.update("setting up recommendation engine");
        rec = new reco(cursor_to_maps(
                    db.rawQuery("SELECT _id, artist, album, title, source, " +
                        "spotify_id, duration, mem_strength FROM songs", null)));

		SharedPreferences settings = PlaybackService.getSettings(context);
        long last_event_in_model = settings.getLong(PrefKeys.LAST_EVENT_IN_MODEL, -1);
        Log.d(C.TAG, "last_event_in_model: " + last_event_in_model);
        //long last_event_in_model = -1;
        //db.execSQL("DELETE FROM model");  // lololol
        //db.execSQL("DELETE FROM artist_model");

        result = db.rawQuery("SELECT song_id, skipped, time, events._id, artist " +
                "FROM events JOIN songs on song_id = songs._id WHERE events._id > ? ORDER BY time ASC",
                new String[] {String.valueOf(last_event_in_model)});
        Log.d(C.TAG, "reading " + result.getCount() + " events");
        if (result.getCount() > 0) {
            listener.update("analyzing listening history");
            // figure out which events are in the current session
            long event_time = System.currentTimeMillis() / 1000;
            long threshold = 60 * 20;  // 20 minutes
            long first_id_in_session = Long.MAX_VALUE;
            result.moveToPosition(result.getCount());
            while (result.moveToPrevious()) {
                String time = result.getString(2);
                try {
                    long seconds = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .parse(time).getTime() / 1000;
                    long delta = event_time - seconds;
                    if (delta > threshold) {
                        break;
                    } else {
                        first_id_in_session = result.getLong(3);
                        event_time = seconds;
                    }
                } catch (ParseException pe) {
                    Log.e(C.TAG, "date couldn't be parsed");
                }
            }

            // read in events
            boolean in_current_session = false;
            result.moveToPosition(-1);
            while (result.moveToNext()) {
                int song_id = result.getInt(0);
                boolean skipped = (result.getInt(1) == 1);
                String time = result.getString(2);
                long id = result.getLong(3);
                String artist = result.getString(4);
                if (id == first_id_in_session) {
                    in_current_session = true;
                }

                try {
                    long seconds = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .parse(time).getTime() / 1000;

                    add_event(song_id, id, skipped, seconds, in_current_session, artist);
                } catch (ParseException pe) {
                    Log.e(C.TAG, "date couldn't be parsed");
                }
                Log.d(C.TAG,
                        "read skip event #" + result.getPosition() +
                        ". in_current_session = " + in_current_session);
            }
        }
        result.close();
        db.close();
        Log.d(C.TAG, "finished moody.init()");
    }

    public boolean refresh_candidates() {
        SQLiteDatabase db = new Database(context).getReadableDatabase();
        boolean ret = rec.refresh_candidates(cursor_to_maps(
                    db.rawQuery("SELECT _id, artist, album, title, source, " +
                        "spotify_id, duration, mem_strength FROM songs", null)));
        db.close();
        return ret;
    }

    public Map get_model(SQLiteDatabase db, long id, String artist) {
        List song_model = cursor_to_maps(db.rawQuery(
                    "select id_a, id_b, score from model where id_a = ?1 or id_b = ?1",
                    new String[] {String.valueOf(id)}));
        if (artist != null) {
            List artist_model = cursor_to_maps(db.rawQuery(
                        "select artist_a, artist_b, score from artist_model where artist_a = ?1 or artist_b = ?1",
                        new String[] {artist}));
            return reco.modelify(song_model, id, artist_model, artist);
        } else {
            return reco.modelify(song_model, id);
        }
    }

    public void update(Song last_song, boolean skipped) {
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
                    String.valueOf(C.ALG_VERSION)});
            add_event(id, null, skipped, null, true, m.artist);
            update_strength(id, db);
        } else {
            Log.e(C.TAG, "couldn't find song in database");
        }
        result.close();
        db.close();
    }

    private void add_event(long song_id, Long event_id, boolean skipped, Long seconds,
            boolean do_update, String artist) {
        SQLiteDatabase db = new Database(context).getWritableDatabase();
        Collection new_model;
        if (seconds == null) {
            new_model = rec.add_event(get_model(db, song_id, artist), song_id, skipped);
        } else {
            new_model = rec.add_event(get_model(db, song_id, artist), song_id, skipped, seconds, do_update);
        }
        if (new_model.size() > 0) {
            Log.d(C.TAG, "updating " + new_model.size() + " parts of model");
            db.beginTransaction();
            for (Map new_model_part : (Collection<Map>)new_model) {
                update_model(new_model_part, db);
            }
            if (event_id == null) {
                event_id = new Long((int)cursor_to_maps(db.rawQuery(
                            "select _id from events order by _id desc limit 1", null))
                    .get(0).get("_id"));
            }
            SharedPreferences.Editor editor =
                PlaybackService.getSettings(context).edit();
            editor.putLong(PrefKeys.LAST_EVENT_IN_MODEL, event_id - 1);
            editor.commit();
            db.setTransactionSuccessful();
            db.endTransaction();
        }
        if (rec.session_size() == 1) {
            if (seconds == null) {
                rec.add_event(get_model(db, -1, null), -1, false);
            } else {
                rec.add_event(get_model(db, -1, null), -1, false, seconds, do_update);
            }
            Log.d(C.TAG, "updating freshness");
            rec.update_freshness(cursor_to_maps(db.rawQuery(
                    "select song_id, time from events", null)),
                    cursor_to_maps(db.rawQuery(
                            "select mem_strength, _id from songs where mem_strength is not null",
                            null)));
        }
        db.close();
    }

    private void update_strength(int song_id, SQLiteDatabase db) {
        double strength = rec.calc_strength(cursor_to_maps(db.rawQuery(
                        "select time, skipped from events where song_id = ?",
                        new String[] {String.valueOf(song_id)})));
        db.execSQL("update songs set mem_strength = ? where _id = ?",
                new String[] {String.valueOf(strength), String.valueOf(song_id)});
    }

    private void update_model(Map model_part, SQLiteDatabase db) {
        if (model_part.get("song_a") == null || model_part.get("song_b") == null) {
            return;
        }

        double score = (double)model_part.get("score");
        long n = (long)model_part.get("n");
        try {
            String song_a = String.valueOf((long)model_part.get("song_a"));
            String song_b = String.valueOf((long)model_part.get("song_b"));
            Cursor old_model = db.rawQuery("select score, n from model where id_a = ? and id_b = ?",
                    new String[] {song_a, song_b});
            if (old_model.getCount() > 0) {
                old_model.moveToPosition(0);
                double old_score = old_model.getDouble(0);
                long old_n = old_model.getLong(1);

                long new_n = old_n + n;
                double new_score = (score * n + old_score * old_n) / new_n;
                db.execSQL("update model set score = ?, n = ? where id_a = ? and id_b = ?",
                        new String[] {String.valueOf(new_score), String.valueOf(new_n),
                            song_a, song_b});
            } else {
                db.execSQL("insert into model (score, n, id_a, id_b) values (?, ?, ?, ?)",
                        new String[] {String.valueOf(score), String.valueOf(n),
                            song_a, song_b});
            }
            old_model.close();
        } catch (ClassCastException e) {
            //Log.e(C.TAG, "uh oh:", e);
            String artist_a = (String)model_part.get("song_a");
            String artist_b = (String)model_part.get("song_b");
            Cursor old_model = db.rawQuery("select score, n from artist_model where artist_a = ? and artist_b = ?",
                    new String[] {artist_a, artist_b});
            if (old_model.getCount() > 0) {
                old_model.moveToPosition(0);
                double old_score = old_model.getDouble(0);
                long old_n = old_model.getLong(1);

                long new_n = old_n + n;
                double new_score = (score * n + old_score * old_n) / new_n;
                db.execSQL("update artist_model set score = ?, n = ? where artist_a = ? and artist_b = ?",
                        new String[] {String.valueOf(new_score), String.valueOf(new_n),
                            artist_a, artist_b});
            } else {
                db.execSQL("insert into artist_model (score, n, artist_a, artist_b) values (?, ?, ?, ?)",
                        new String[] {String.valueOf(score), String.valueOf(n),
                            artist_a, artist_b});
            }
            old_model.close();
        }
    }

    public Metadata pick_next(boolean local_only) {
        Map ret = rec.pick_next(local_only);
        return (ret == null) ? null : new Metadata(ret);
    }

    public static void add_to_library(Context c, List<Metadata> songs) {
        SQLiteDatabase db = new Database(c).getWritableDatabase();
        db.beginTransaction();
        int count = 0;
        for (Metadata s : songs) {
            String query = "select _id, source from songs where " + match_clause(s);
            Cursor result = db.rawQuery(query, s.query());

            result.moveToFirst();
            if (result.getCount() == 0) {
                count++;
                db.execSQL("INSERT INTO songs (artist, album, title, duration, source, spotify_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                        new String[] {s.artist, s.album, s.title, String.valueOf(s.duration),
                                s.source, s.spotify_id});
            } else if ("local".equals(s.source) &&
                        !("local".equals(result.getString(1)))) {
                query = "UPDATE songs SET source = \"local\" WHERE _id = ?";
                db.execSQL(query, new String[] {String.valueOf(result.getInt(0))});
            }
            result.close();
        }
        db.setTransactionSuccessful();
        db.endTransaction();
        db.close();
        Log.d(C.TAG, "added " + count + " songs to library");
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


    public static List<Map<String, Object>> cursor_to_maps(Cursor c) {
        List<Map<String, Object>> list = new ArrayList<>();
        int count = c.getColumnCount();
        c.moveToPosition(-1);
        while (c.moveToNext()) {
            Map<String, Object> m = new HashMap<>();
            for (int i = 0; i < count; i++) {
                String key = c.getColumnName(i);
                Object value = null;
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
        c.close();
        return list;
    }

    public static boolean spotify_token_expired(Context context) {
		SharedPreferences settings = PlaybackService.getSettings(context);
        return System.currentTimeMillis() / 1000 >
            settings.getLong(PrefKeys.SPOTIFY_TOKEN_EXPIRATION, 0);
    }

    public static boolean wants_spotify(Context context) {
		SharedPreferences settings = PlaybackService.getSettings(context);
        return settings.getBoolean(PrefKeys.WANTS_SPOTIFY, false);
    }

    public static boolean already_asked_about_spotify(Context context) {
		SharedPreferences settings = PlaybackService.getSettings(context);
        return settings.getBoolean(PrefKeys.ALREADY_ASKED, false);
    }

    public static void set_already_asked(Context context) {
        SharedPreferences.Editor editor =
            PlaybackService.getSettings(context).edit();
        editor.putBoolean(PrefKeys.ALREADY_ASKED, true);
        editor.commit();
    }

    public static void wants_spotify(Context context, boolean wants) {
        SharedPreferences.Editor editor =
            PlaybackService.getSettings(context).edit();
        editor.putBoolean(PrefKeys.WANTS_SPOTIFY, wants);
        editor.commit();
    }

    public void add_to_blacklist(long id) {
        rec.add_to_blacklist(id);
    }

    public static class SpotifyTask extends AsyncTask<Void, String, Void> {
        ProgressDialog dialog;
        Context c;
        NaviListener navi;

        public SpotifyTask(Context c, NaviListener navi) {
            super();
            this.dialog = new ProgressDialog(c);
            this.c = c;
            this.navi = navi;
            dialog.setTitle("Getting Spotify songs");
            dialog.setCancelable(false);
            dialog.show();
        }

        @Override
        protected Void doInBackground(Void... v) {
            Log.d(C.TAG, "doing SpotifyTask");
            try {
                SyncAdapter.getSpotifySongs(this.c);
                Moody.getInstance(this.c, new InitProgressListener() {
                    public void update(String s) {
                        publishProgress(s);
                    }
                }).refresh_candidates();
                navi.hey_listen();
            } catch (IOException e) {
                Log.e(C.TAG, "couldn't get spotify songs", e);
            }
            Log.d(C.TAG, "finished SpotifyTask");
            return null;
        }

        @Override
        protected void onPostExecute(Void v) {
            dialog.dismiss();
        }

        @Override
        protected void onProgressUpdate(String... s) {
            dialog.setMessage(s[0]);
        }
    }

    public static class InitTask extends AsyncTask<Void, String, Void> {
        ProgressDialog dialog;
        Context c;

        public InitTask(Context c) {
            super();
            this.dialog = new ProgressDialog(c);
            this.c = c;
            dialog.setTitle("Loading model");
            dialog.setCancelable(false);
            dialog.show();
        }

        @Override
        protected void onProgressUpdate(String... s) {
            dialog.setMessage(s[0]);
        }

        @Override
        protected Void doInBackground(Void... v) {
            Moody.getInstance(c, new InitProgressListener() {
                public void update(String s) {
                    publishProgress(s);
                }
            });
            return null;
        }

        @Override
        protected void onPostExecute(Void v) {
            dialog.dismiss();
        }
    }

    interface InitProgressListener {
        void update(String foo);
    }

    public interface NaviListener {
        void hey_listen();
    }
}
