package com.jacobobryant.moody;

import com.jacobobryant.moody.vanilla.Song;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class Metadata {
    public Long duration;
    public String spotify_id;
    public String source;
    public String title;
    public String album;
    public String artist;
    public Double danceability;
    public Double energy;
    public Double mode;
    public Double speechiness;
    public Double acousticness;
    public Double instrumentalness;
    public Double liveness;
    public Double valence;


    public Metadata(String artist, String album, String title) {
        this.artist = artist;
        this.album = album;
        this.title = title;
    }

    public Metadata(Map<String, Object> m) {
        this();
        this.title = (String)m.get("title");
        this.artist = (String)m.get("artist");
        this.album = (String)m.get("album");
        this.duration = (Long)m.get("duration");
        this.source = (String)m.get("source");
        this.spotify_id = (String)m.get("spotify_id");
    }

    public Metadata(Song s) {
        this(s.artist, s.album, s.title);
    }

    public Metadata() { }

    public String[] query() {
        List<String> args = new LinkedList<>();
        for (String arg : new String[] { artist, album, title}) {
            if (arg != null) {
                args.add(arg);
            }
        }
        return args.toArray(new String[args.size()]);
    }


    public Map<String, String> toMap() {
        Map<String, String> foo = new HashMap<>();
        foo.put("artist", artist);
        foo.put("album", album);
        foo.put("title", title);
        return foo;
    }
}
