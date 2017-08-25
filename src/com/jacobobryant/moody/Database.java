package com.jacobobryant.moody;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class Database extends SQLiteOpenHelper {
    public static final int VERSION = 7;
    public static final String FILE = "moody.db";
    public static final String MODEL_SQL =
        "create table model (" +
        "_id integer primary key autoincrement, " +
        "id_a integer, " +
        "id_b integer, " +
        "score real, " +
        "n integer, " +
        "unique(id_a, id_b))";
    public static final String ARTIST_MODEL_SQL =
        "create table artist_model (" +
        "_id integer primary key autoincrement, " +
        "artist_a text, " +
        "artist_b text, " +
        "score real, " +
        "n integer, " +
        "unique(artist_a, artist_b))";


    public Database(Context context) {
        super(context, FILE, null, VERSION);
    }

    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE songs (" +
                   "_id              INTEGER PRIMARY KEY AUTOINCREMENT, " +
                   "title            TEXT, " +
                   "artist           TEXT, " +
                   "album            TEXT, " +
                   "duration         INTEGER, " +
                   "source           TEXT, " +
                   "mem_strength     REAL, " +
                   "spotify_id       TEXT, " +
                   "danceability     REAL, " +
                   "energy           REAL, " +
                   "mode             REAL, " +
                   "speechiness      REAL, " +
                   "acousticness     REAL, " +
                   "instrumentalness REAL, " +
                   "liveness         REAL, " +
                   "valence          REAL, " +          
                   "UNIQUE(title, artist, album))");
        db.execSQL("CREATE TABLE events (" +
                   "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                   "song_id INTEGER, " +
                   "skipped INTEGER, " +
                   "algorithm INTEGER, " +
                   "time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                   "FOREIGN KEY (song_id) REFERENCES songs(_id))");
        db.execSQL(MODEL_SQL);
        db.execSQL(ARTIST_MODEL_SQL);
    }

    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        //if (oldVersion == 4) {
        //    db.execSQL("ALTER TABLE events ADD COLUMN mood INTEGER");
        //    db.execSQL("UPDATE events SET mood = 0 WHERE mood IS NULL");
        //    oldVersion++;
        //}
        if (oldVersion < 5) {
            oldVersion = 5;
            db.beginTransaction();

            db.execSQL("ALTER TABLE songs ADD COLUMN duration INTEGER");
            db.execSQL("ALTER TABLE songs ADD COLUMN source TEXT");
            db.execSQL("ALTER TABLE songs ADD COLUMN spotify_id TEXT");
            db.execSQL("ALTER TABLE songs ADD COLUMN danceability REAL");
            db.execSQL("ALTER TABLE songs ADD COLUMN energy REAL");
            db.execSQL("ALTER TABLE songs ADD COLUMN mode REAL");
            db.execSQL("ALTER TABLE songs ADD COLUMN speechiness REAL");
            db.execSQL("ALTER TABLE songs ADD COLUMN acousticness REAL");
            db.execSQL("ALTER TABLE songs ADD COLUMN instrumentalness REAL");
            db.execSQL("ALTER TABLE songs ADD COLUMN liveness REAL");
            db.execSQL("ALTER TABLE songs ADD COLUMN valence REAL");          

            db.execSQL("CREATE TABLE events_backup (" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "song_id INTEGER, " +
                    "skipped INTEGER, " +
                    "algorithm INTEGER, " +
                    "time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "FOREIGN KEY (song_id) REFERENCES songs(_id))");
            db.execSQL("INSERT INTO events_backup SELECT " +
                    "_id, song_id, skipped, algorithm, time FROM events");
            db.execSQL("DROP TABLE events");
            db.execSQL("ALTER TABLE events_backup RENAME TO events");

            db.setTransactionSuccessful();
            db.endTransaction();
        }
        if (oldVersion == 5) {
            oldVersion++;
            db.execSQL(MODEL_SQL);
            db.execSQL(ARTIST_MODEL_SQL);
        }
        if (oldVersion == 6) {
            db.execSQL("ALTER TABLE songs ADD COLUMN mem_strength REAL");
        }
    }
}
