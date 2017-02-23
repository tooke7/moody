package com.jacobobryant.moody;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.provider.MediaStore;
import android.util.Log;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ch.blinkenlights.android.vanilla.Song;

import static android.R.attr.key;

public class Moody {
    private static Moody instance;
    private Context context;
    private Map<Metadata, Ratio> ratios;

    private Moody() { }

    public static Moody getInstance(Context context) {
        if (instance == null) {
            instance = new Moody();
            instance.context = context;
        }
        return instance;
    }

    public void populate() {
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
        Set<String> library = new HashSet<String>(Arrays.asList(
                    //"It Has Begun",
                    //"Make a Move",
                    //"Let It Die (Acoustic)",
                    //"Pieces",
                    //"My Demons (Synchronice Remix)",
                    //"Off With Her Head"
                    "Paper Wings",
                    "The Diary Of Jane",
                    "Crystallize"
        ));

        // get metadata for all the user's songs
        result.moveToPosition(-1);
        while (result.moveToNext()) {
            String title = result.getString(0);
            String artist = result.getString(1);
            String album = result.getString(2);
            long duration = result.getLong(3);

            // limit library for testing
            //Log.d(C.TAG, "artist=" + artist);
            //if (!library.contains(title)) {
            //    continue;
            //}

            // TODO change ON CONFLICT thing to UPDATE?
            db.execSQL("INSERT INTO songs (artist, album, title, duration) VALUES (?, ?, ?, ?)",
                    new String[] {artist, album, title, String.valueOf(duration)});

            // initialize ratios
            Metadata m = new Metadata(artist, album, title);
            do {
                ratios.put(m, new Ratio());
                m = m.pop();
            } while (m != null);
        }
        //Log.d(C.TAG, "ratios.size()=" + String.valueOf(ratios.size()));

        db.setTransactionSuccessful();
        db.endTransaction();
        result.close();

        // read in past skip data
        result = db.rawQuery("SELECT artist, album, title, position, " +
                "duration FROM songs JOIN events ON songs._id = events.song_id",
                null);
        result.moveToPosition(-1);
        while (result.moveToNext()) {
            String artist = result.getString(0);
            String album = result.getString(1);
            String title = result.getString(2);
            long position = result.getLong(3);
            long duration = result.getLong(4);
            update_all(artist, album, title, position, duration);
        }
        result.close();

        db.close();
    }

    public void update(Song last_song, long position) {
        if (ratios == null) {
            throw new RuntimeException("populate() hasn't been called");
        }
        Log.d(C.TAG, "last song: " + last_song.title + ", " +
                last_song.album + ", " + last_song.artist);

        update_all(last_song.artist, last_song.album, last_song.title,
                position, last_song.duration);

        // update db
        // TODO do this in one query
        SQLiteDatabase db = new Database(context).getWritableDatabase();
        Cursor result = db.rawQuery(
                "SELECT _id FROM songs WHERE artist = ? AND album = ? " +
                "AND title = ?",
                new String[] {last_song.artist, last_song.album,
                    last_song.title});
        int id;
        if (result.getCount() > 0) {
            result.moveToPosition(0);
            id = result.getInt(0);
        } else {
            db.beginTransaction();
            db.execSQL("INSERT INTO songs (artist, album, title, duration) " +
                       "VALUES (?, ?, ?, ?)",
                    new String[] {last_song.artist, last_song.album, last_song.title,
                        String.valueOf(last_song.duration)});
            Cursor idCursor = db.rawQuery("SELECT last_insert_rowid()", null);
            idCursor.moveToPosition(0);
            id = idCursor.getInt(0);
            idCursor.close();
            db.setTransactionSuccessful();
            db.endTransaction();
        }
        db.execSQL("INSERT INTO events (song_id, position) VALUES (?, ?)",
                new String[]{String.valueOf(id), String.valueOf(position)});
        result.close();
        db.close();

    }

    public Metadata pick_next() {
        if (ratios == null) {
            throw new RuntimeException("populate() hasn't been called");
        }

        // calculate the probability for all songs
        List<Probability> probs = new LinkedList<>();
        double probSum = 0;
        for (Map.Entry<Metadata, Ratio> entry : ratios.entrySet()) {
            Metadata key = entry.getKey();
            if (key.type != Metadata.Type.SONG) {
                continue;
            }
            Probability p = new Probability(ratios, key);
            probSum += p.prob;
            probs.add(p);
        }
        //Log.d(C.TAG, "probs.size()=" + String.valueOf(probs.size()));

        // pick a song to play next
        // TODO use BigDecimal to prevent drift
        Metadata choice = null;
        double x = Math.random() * probSum;
        for (Probability prob : probs) {
            if (x < prob.prob) {
                choice = prob.m;
                break;
            }
            x -= prob.prob;
        }
        if (choice == null) {
            Log.d(C.TAG, "shuffle choice out of range");
            choice = probs.get(probs.size() - 1).m;
        }

        return choice;
    }

    public void update_all(String artist, String album, String title,
            long position, long duration) {
        boolean skipped = position / (double)duration < 0.5;
        Metadata key = new Metadata(artist, album, title);
        do {
            try {
                ratios.get(key).update(skipped);
            } catch (NullPointerException e) {
                Log.e(C.TAG, "ratio for " + key + " doesn't exist");
            }
            key = key.pop();
        } while (key != null);
    }
}
