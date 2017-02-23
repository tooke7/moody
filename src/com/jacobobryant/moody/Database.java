package com.jacobobryant.moody;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class Database extends SQLiteOpenHelper {
    public static final int VERSION = 2;
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
                   "duration INTEGER, " +
                   "UNIQUE(title, artist, album) ON CONFLICT IGNORE)");
        db.execSQL("CREATE TABLE events (" +
                   "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                   "song_id INTEGER, " +
                   "position INTEGER, " +
                   "time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                   "FOREIGN KEY (song_id) REFERENCES songs(_id))");
    }

    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE songs");
        db.execSQL("DROP TABLE events");
        onCreate(db);
    }
}
