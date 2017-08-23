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

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import reco.reco;

import static android.database.Cursor.FIELD_TYPE_BLOB;
import static android.database.Cursor.FIELD_TYPE_FLOAT;
import static android.database.Cursor.FIELD_TYPE_INTEGER;
import static android.database.Cursor.FIELD_TYPE_STRING;

public class Moody {
    public static final String STATE_FILE = "reco_state";
    private static Moody instance;
    private Context context;
    public static final String AUTHORITY = "com.jacobobryant.moody.vanilla";
    public static final String ACCOUNT_TYPE = "com.jacobobryant";
    public static final String ACCOUNT = "moodyaccount";
    private Account newAccount;
    private reco rec;
    public boolean next_on_blacklist = false;
    private boolean was_random;

    private Moody() { }

    public static Moody getInstance(Context context,
            InitProgressListener listener) {
        if (instance == null) {
            Log.d(C.TAG, "package name:" + context.getPackageName());
            instance = new Moody();
            instance.context = context;
            instance.init(listener);
        }
        return instance;
    }

    public static Moody getInstance(Context context) {
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

        // get library
        listener.update("setting up recommendation engine");
        SQLiteDatabase db = new Database(context).getReadableDatabase();
        result = db.rawQuery("SELECT _id, artist, album, title, source, " +
                "spotify_id, duration FROM songs", null);
        rec = new reco(cursor_to_maps(result));
        result.close();

        long last_event_id = -1;
        try {
            Log.d(C.TAG, "loading listening data from cache");
            listener.update("loading listening history from cache");
            FileInputStream fin = context.openFileInput(STATE_FILE);
            ObjectInputStream ois = new ObjectInputStream(fin);
            Map state = (Map)ois.readObject();
            ois.close();
            fin.close();
            rec.set_state(state);
            last_event_id = rec.get_last_event_id();
        } catch (IOException | ClassNotFoundException e) { 
            Log.e(C.TAG, "couldn't load cache");
        }

        result = db.rawQuery("SELECT song_id, skipped, time, _id " +
                "FROM events WHERE _id > ? ORDER BY time ASC",
                new String[] {String.valueOf(last_event_id)});
        Log.d(C.TAG, "reading " + result.getCount() + " skip events");
        if (result.getCount() > 0) {
            listener.update("analyzing listening history");
            // figure out which events are in the current session
            long event_time = System.currentTimeMillis() / 1000;
            long threshold = 60 * 20;  // 20 minutes
            long first_id_in_session = -1;
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
                if (id == first_id_in_session) {
                    in_current_session = true;
                }

                try {
                    long seconds = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .parse(time).getTime() / 1000;

                    rec.add_event(song_id, skipped, seconds, in_current_session);
                    rec.set_last_event_id(id);
                } catch (ParseException pe) {
                    Log.e(C.TAG, "date couldn't be parsed");
                }
                Log.d(C.TAG,
                        "read skip event #" + result.getPosition() +
                        ". in_current_session = " + in_current_session);
            }
            //new SaveStateTask().execute(rec);
            listener.update("saving recommendation model");
            saveState(context, rec);
        }
        result.close();
        db.close();
        Log.d(C.TAG, "finished moody.init()");
    }

    public void update(Song last_song, boolean skipped) {
        if (next_on_blacklist) {
            //rec.add_to_blacklist(new Metadata(last_song).toMap());
            next_on_blacklist = false;
            return;
        }

        // update db
        SQLiteDatabase db = new Database(context).getWritableDatabase();
        Metadata m = new Metadata(last_song);
        Cursor result = db.rawQuery("SELECT _id FROM songs WHERE "
                + match_clause(m), m.query());
        if (result.getCount() > 0) {
            result.moveToPosition(0);
            int id = result.getInt(0);
            int algorithm = (was_random) ? 0 : C.ALG_VERSION;
            db.execSQL("INSERT INTO events (song_id, skipped, algorithm) VALUES (?, ?, ?)",
                    new String[]{String.valueOf(id), String.valueOf(skipped ? 1 : 0),
                    String.valueOf(algorithm)});


            List song_model = cursor_to_maps(db.rawQuery(
                        "select id_a, id_b, score from model where id_a = ?1 or id_b = ?1",
                        new String[] {String.valueOf(id)}));
            Map model;
            if (m.artist != null) {
                List artist_model = cursor_to_maps(db.rawQuery(
                            "select artist_a, artist_b, score from artist_model where artist_a = ?1 or artist_b = ?1",
                            new String[] {m.artist}));
                model = rec.modelify(song_model, artist_model);
            } else {
                model = rec.modelify(song_model);
            }

            db.beginTransaction();
            for (Map new_model_part : rec.add_event(model, id, skipped)) {
                update_model(new_model_part, db);
            }
            db.setTransactionSuccessful();
            db.endTransaction();
        } else {
            Log.e(C.TAG, "couldn't find song in database");
        }
        result.close();
        db.close();
    }

    private void update_model(Map model_part, SQLiteDatabase db) {
        double score = (double)model_part.get("score");
        int n = (int)model_part.get("n");
        try {
            String song_a = String.valueOf((int)model_part.get("song_a"));
            String song_b = String.valueOf((int)model_part.get("song_b"));
            Cursor old_model = db.rawQuery("select score, n from model where id_a = ? and id_b = ?",
                    new String[] {song_a, song_b});
            if (old_model.getCount() > 0) {
                old_model.moveToPosition(0);
                double old_score = old_model.getDouble(0);
                int old_n = old_model.getInteger(1);

                int new_n = old_n + n;
                double new_score = (score * n + old_score * old_n) / new_n;
                db.execSQL("update model set score = ?, n = ? where id_a = ? and id_b = ?",
                        new String[] {String.valueOf(new_score), String.valueOf(new_n),
                            song_a, song_b});
            } else {
                db.execSQL("insert into model (score, n, id_a, id_b) values (?, ?, ?, ?)",
                        new String[] {String.valueOf(score), String.valueOf(n),
                            song_a, song_b});
            }
        } catch (ClassCastException e) {
            String artist_a = (String)model_part.get("song_a");
            String artist_b = (String)model_part.get("song_b");
            Cursor old_model = db.rawQuery("select score, n from artist_model where artist_a = ? and artist_b = ?",
                    new String[] {artist_a, artist_b});
            if (old_model.getCount() > 0) {
                old_model.moveToPosition(0);
                double old_score = old_model.getDouble(0);
                int old_n = old_model.getInteger(1);

                int new_n = old_n + n;
                double new_score = (score * n + old_score * old_n) / new_n;
                db.execSQL("update artist_model set score = ?, n = ? where artist_a = ? and artist_b = ?",
                        new String[] {String.valueOf(new_score), String.valueOf(new_n),
                            artist_a, artist_b});
            } else {
                db.execSQL("insert into artist_model (score, n, artist_a, artist_b) values (?, ?, ?, ?)",
                        new String[] {String.valueOf(score), String.valueOf(n),
                            artist_a, artist_b});
            }
        }
    }


    public Metadata pick_next(boolean local_only) {
        float CONTROL_PROB = 0.0f;
        was_random = Math.random() < CONTROL_PROB;
        Map ret = (was_random) ? rec.pick_random(local_only) :
                                 rec.pick_next(local_only);
        return (ret == null) ? null : new Metadata(ret);
    }

    public static void add_to_library(Context c, List<Metadata> songs) {
        SQLiteDatabase db = new Database(c).getWritableDatabase();
        db.beginTransaction();
        for (Metadata s : songs) {
            String query = "select _id, source from songs where " + match_clause(s);
            Cursor result = db.rawQuery(query, s.query());

            result.moveToFirst();
            if (result.getCount() == 0) {
                db.execSQL("INSERT INTO songs (artist, album, title, duration, source, spotify_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                        new String[] {s.artist, s.album, s.title, String.valueOf(s.duration),
                                s.source, s.spotify_id});
            } else if ("local".equals(s.source) &&
                        !("local".equals(result.getString(1)))) {
                query = "UPDATE songs SET source = \"local\" WHERE _id = ?";
                db.execSQL(query, new String[] {String.valueOf(result.getInt(0))});
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
        return list;
    }

    public static boolean spotify_token_expired(Context context) {
		SharedPreferences settings = PlaybackService.getSettings(context);
        return System.currentTimeMillis() / 1000 >
            settings.getLong(PrefKeys.SPOTIFY_TOKEN_EXPIRATION, 0);
    }

    public void add_to_blacklist(long id) {
        rec.add_to_blacklist(id);
    }

    private class SaveStateTask extends AsyncTask<reco, Void, Void> {
        protected Void doInBackground(reco... rec) {
            saveState(context, rec[0]);
            return null;
        }
    }

    public static void saveState(Context context, reco rec) {
        try {
            Log.d(C.TAG, "saving state");
            FileOutputStream fout = context.openFileOutput(
                    STATE_FILE, Context.MODE_PRIVATE);
            ObjectOutputStream oos = new ObjectOutputStream(fout);
            oos.writeObject(rec.get_state());
            oos.close();
            fout.close();
        } catch (IOException e) {
            Log.e(C.TAG, "couldn't save state");
            try {
                FileOutputStream fout = context.openFileOutput(
                        STATE_FILE, Context.MODE_PRIVATE);
                fout.write(null);
                fout.close();
            } catch (IOException e2) {
                Log.e(C.TAG, "couldn't delete cache");
            }
        }
        Log.d(C.TAG, "finished saving state");
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
}
