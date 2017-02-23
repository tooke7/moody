package com.jacobobryant.moody;

import java.util.LinkedList;
import java.util.List;

import static com.jacobobryant.moody.Metadata.Type.*;

public class Metadata {
    public String title;
    public String album;
    public String artist;
    public Type type;

    public enum Type {
        GLOBAL, ARTIST, ALBUM, SONG
    }

    public Metadata(String artist, String album, String title) {
        this.type = SONG;
        this.artist = artist;
        this.album = album;
        this.title = title;
    }

    public Metadata(String artist, String album) {
        this.type = ALBUM;
        this.artist = artist;
        this.album = album;
    }

    public Metadata(String artist) {
        this.type = ARTIST;
        this.artist = artist;
    }

    public Metadata() {
        this.type = GLOBAL;
    }

    public Metadata pop() {
        switch (type) {
            case SONG:
                return new Metadata(artist, album);
            case ALBUM:
                return new Metadata(artist);
            case ARTIST:
                return new Metadata();
            default:
                return null;
        }
    }

    public String[] query() {
        if (type != SONG) {
            throw new RuntimeException("can't construct query: type=" + type);
        }

        List<String> args = new LinkedList<>();
        for (String arg : new String[] { artist, album, title}) {
            if (arg != null) {
                args.add(arg);
            }
        }
        return args.toArray(new String[args.size()]);
    }

    @Override
    public String toString() {
        return "Metadata{" +
                "title='" + title + '\'' +
                ", album='" + album + '\'' +
                ", artist='" + artist + '\'' +
                ", type=" + type +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Metadata metadata = (Metadata) o;

        if (title != null ? !title.equals(metadata.title) : metadata.title != null) return false;
        if (album != null ? !album.equals(metadata.album) : metadata.album != null) return false;
        if (artist != null ? !artist.equals(metadata.artist) : metadata.artist != null)
            return false;
        return type == metadata.type;

    }

    @Override
    public int hashCode() {
        int result = title != null ? title.hashCode() : 0;
        result = 31 * result + (album != null ? album.hashCode() : 0);
        result = 31 * result + (artist != null ? artist.hashCode() : 0);
        result = 31 * result + (type != null ? type.hashCode() : 0);
        return result;
    }
}
