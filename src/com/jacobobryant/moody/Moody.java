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

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.jacobobryant.moody.vanilla.BuildConfig;
import com.jacobobryant.moody.vanilla.PlaybackService;
import com.jacobobryant.moody.vanilla.PrefDefaults;
import com.jacobobryant.moody.vanilla.PrefKeys;
import com.jacobobryant.moody.vanilla.Song;

import reco.reco;

public class Moody {
    private static Moody instance;
    private Context context;
    // maps moods (integers) to the ratio data.
    private Map<Integer, Map<Metadata, Ratio>> ratios;
    //private Map<Metadata, Ratio> ratios;
    //private final Map<Metadata, Ratio> skel;
    private Set<Metadata> library;
    private List<Metadata> songs;
    private Metadata random_song;
    private static final int RANDOM_MOOD = -1;
    public static final String AUTHORITY = "com.jacobobryant.moody.vanilla";
    public static final String ACCOUNT_TYPE = "com.jacobobryant";
    public static final String ACCOUNT = "moodyaccount";
    private Account newAccount;
    private reco rec;

    private Moody() { }

    public static Moody getInstance(Context context) {
        if (instance == null) {
            instance = new Moody();
            instance.context = context;
        }
        return instance;
    }

    public void init() {
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
        ratios = new HashMap<>();
        db.beginTransaction();

        library = new HashSet<>();
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

            // initialize ratios
            Metadata m = new Metadata(artist, album, title);
            songs.add(m);
            do {
                library.add(m);
                m = m.pop();
            } while (m != null);
        }

        db.setTransactionSuccessful();
        db.endTransaction();
        result.close();
        rec = new reco(conv(songs));

        // read in past skip data
        result = db.rawQuery("SELECT artist, album, title, skipped, mood, time " +
                "FROM songs JOIN events ON songs._id = events.song_id", null);
        result.moveToPosition(-1);
        while (result.moveToNext()) {
            String artist = result.getString(0);
            String album = result.getString(1);
            String title = result.getString(2);

            boolean skipped = (result.getInt(3) == 1);
            //boolean skipped = result.getString(3).equals("true");
            int mood = result.getInt(4);
            String time = result.getString(5);

            long seconds;
            try {
                seconds = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .parse(time).getTime() / 1000;
            } catch (ParseException e) {
                Log.e(C.TAG, "date couldn't be parsed");
                continue;
            }

            rec.add_event(artist, album, title, skipped, seconds);
            update_ratios(artist, album, title, skipped, mood);
        }
        result.close();

        db.close();

    }

    public void update(Song last_song, boolean skipped) {
        if (ratios == null) {
            throw new RuntimeException("init() hasn't been called");
        }
        //Log.d(C.TAG, "last song: " + last_song.title + ", " +
        //        last_song.album + ", " + last_song.artist);

        // get the current algorithm. 0 means random.
        int algorithm;
        if (random_song != null && random_song.equals(new Metadata(last_song.artist,
                    last_song.album, last_song.title))) {
            algorithm = 0;
            random_song = null;
        } else {
            algorithm = C.ALG_VERSION;
        }
        int mood = get_mood();
        rec.add_event(last_song.artist, last_song.album, last_song.title, skipped);
        update_ratios(last_song.artist, last_song.album, last_song.title,
                skipped, mood);

        // update db
        // TODO do this in one query
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
        db.execSQL("INSERT INTO events (song_id, skipped, mood, algorithm) VALUES (?, ?, ?, ?)",
                new String[]{String.valueOf(id), String.valueOf(skipped ? 1 : 0),
                String.valueOf(mood), String.valueOf(algorithm)});
        result.close();
        db.close();
    }

    public Metadata pick_next() {
        Metadata choice = new Metadata(rec.pick_next());
        Log.d(C.TAG, "New alg choice: " + choice);
        return choice;


        //float CONTROL_PROB = 0.075f;
        //double OLD_PROB = CONTROL_PROB + (1.0 - CONTROL_PROB) / 2;
        //if (ratios == null) {
        //    throw new RuntimeException("init() hasn't been called");
        //}

        //// suggest a random song every now and then for evaluation purposes.
        //double x = Math.random();
        //if (x < CONTROL_PROB) {
        //    int item = new Random().nextInt(songs.size());
        //    random_song = songs.get(item);
        //    Log.d(C.TAG, "suggesting random song: " + random_song);
        //    return random_song;
        //} else if (x >= OLD_PROB) {
        //    Metadata choice = new Metadata(rec.pick_next());
        //    Log.d(C.TAG, "New alg choice: " + choice);
        //    return choice;
        //}

        //Map<Metadata, Ratio> mratios = get_ratios(get_mood());

        //// calculate the probability for all songs
        //List<Probability> probs = new LinkedList<>();
        //double probSum = 0;
        //for (Map.Entry<Metadata, Ratio> entry : mratios.entrySet()) {
        //    Metadata key = entry.getKey();
        //    if (key.type != Metadata.Type.SONG) {
        //        continue;
        //    }
        //    Probability p = new Probability(mratios, key);
        //    probSum += p.prob;
        //    probs.add(p);
        //}
        ////Log.d(C.TAG, "probs.size()=" + String.valueOf(probs.size()));

        //// pick a song to play next
        //// TODO use BigDecimal to prevent drift
        //Metadata choice = null;
        //x = Math.random() * probSum;
        //for (Probability prob : probs) {
        //    if (x < prob.prob) {
        //        choice = prob.m;
        //        break;
        //    }
        //    x -= prob.prob;
        //}
        //if (choice == null) {
        //    Log.w(C.TAG, "shuffle choice out of range");
        //    choice = probs.get(probs.size() - 1).m;
        //}

        //return choice;
    }

    private void update_ratios(String artist, String album, String title,
            boolean skipped, int mood) {
        Map<Metadata, Ratio> mood_ratios = get_ratios(mood);
        Metadata key = new Metadata(artist, album, title);

        Log.d(C.TAG, "title=" + title + ", skipped=" + skipped);
        do {
            try {
                mood_ratios.get(key).update(skipped);
            } catch (NullPointerException e) {
                Log.e(C.TAG, "ratio for " + key + " doesn't exist");
            }
            key = key.pop();
        } while (key != null);
    }

    private Map<Metadata, Ratio> get_ratios(int mood) {
        Map<Metadata, Ratio> mood_ratios = ratios.get(mood);
        if (mood_ratios == null) {
            mood_ratios = create_ratios();
            ratios.put(mood, mood_ratios);
        }
        return mood_ratios;
    }

    private Map<Metadata, Ratio> create_ratios() {
        Map<Metadata, Ratio> r = new HashMap<>();
        for (Metadata m : library) {
            r.put(m, new Ratio());
        }
        return r;
    }

    public int get_mood() {
		SharedPreferences settings = PlaybackService.getSettings(context);
		return settings.getInt(PrefKeys.MOOD, PrefDefaults.MOOD);
    }

    public void test() {
        //try {
        Log.d(C.TAG, "begin test");

        //SyncAdapter.sync(context);
        Log.d(C.TAG, rec.testing());


        Log.d(C.TAG, "finish test");
        //} catch (IOException e) {
        //    throw new RuntimeException(e);
        //}
    }

    public List<Map<String, String>> conv(List<Metadata> songs) {
        List<Map<String, String>> ret = new ArrayList<>();
        for (Metadata song : songs) {
            ret.add(song.toMap());
        }
        return ret;
    }
}
