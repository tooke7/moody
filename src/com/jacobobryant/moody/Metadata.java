package com.jacobobryant.moody;

import com.jacobobryant.moody.vanilla.Song;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class Metadata {
    public Integer id;
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
        this.id = (Integer)m.get("_id");
        this.title = (String)m.get("title");
        this.artist = (String)m.get("artist");
        this.album = (String)m.get("album");
        try {
            this.duration = new Long((Integer)m.get("duration"));
        } catch (ClassCastException e) {
            try {
                this.duration = (Long)m.get("duration");
            } catch (ClassCastException e2) {
                try {
                    this.duration = Long.parseLong((String)m.get("duration"));
                } catch (Exception e3) {
                    this.duration = 0L;
                }
            }
        }
        this.source = (String)m.get("source");
        this.spotify_id = (String)m.get("spotify_id");
    }

    public Metadata(Song s) {
        this(s.artist, s.album, s.title);
    }

    public Metadata() { }

    public Song toSong() {
        Song s = new Song(-1);
        s.path = spotify_id;
        s.title = title;
        s.artist = artist;
        s.album = album;
        s.duration = duration;
        return s;
    }

    public String[] query() {
        List<String> args = new LinkedList<>();
        for (String arg : new String[] { artist, album, title}) {
            if (arg != null) {
                args.add(arg);
            }
        }
        return args.toArray(new String[args.size()]);
    }
}
