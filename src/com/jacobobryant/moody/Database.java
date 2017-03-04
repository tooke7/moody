package com.jacobobryant.moody;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class Database extends SQLiteOpenHelper {
    public static final int VERSION = 4;
    public static final String FILE = "moody.db";

    public Database(Context context) {
        super(context, FILE, null, VERSION);
    }

    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE songs (" +
                   "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                   "title TEXT, " +
                   "artist TEXT, " +
                   "album TEXT, " +
                   "UNIQUE(title, artist, album) ON CONFLICT IGNORE)");
        db.execSQL("CREATE TABLE events (" +
                   "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                   "song_id INTEGER, " +
                   "skipped INTEGER, " +
                   "mood INTEGER, " +
                   "time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                   "FOREIGN KEY (song_id) REFERENCES songs(_id))");
    }

    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        //if (oldVersion == 2) {
        //    db.execSQL("ALTER TABLE events ADD COLUMN mood INTEGER");
        //    db.execSQL("UPDATE events SET mood = 0 WHERE mood IS NULL");
        //    oldVersion++;
        //}
        db.execSQL("DROP TABLE songs");
        db.execSQL("DROP TABLE events");
        onCreate(db);
    }
}
