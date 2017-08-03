/*
 * Copyright (C) 2015 Adrian Ulrich <adrian@blinkenlights.ch>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>. 
 */


package com.jacobobryant.moody.vanilla;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.media.audiofx.AudioEffect;
import android.os.Build;
import android.util.Log;

import com.jacobobryant.moody.C;
import com.spotify.sdk.android.player.Config;
import com.spotify.sdk.android.player.ConnectionStateCallback;
import com.spotify.sdk.android.player.PlaybackState;
import com.spotify.sdk.android.player.Player;
import com.spotify.sdk.android.player.PlayerEvent;
import com.spotify.sdk.android.player.Spotify;
import com.spotify.sdk.android.player.SpotifyPlayer;

import java.io.FileInputStream;
import java.io.IOException;

public class VanillaMediaPlayer extends MediaPlayer {

	private Context mContext;
	private String mDataSource;
	private boolean mHasNextMediaPlayer;
	private float mReplayGain = Float.NaN;
	private float mDuckingFactor = Float.NaN;
	private boolean mIsDucking = false;
    private SpotPlayer mSP;
    private MediaPlayer.OnCompletionListener mCompletionListener;

    public class SpotPlayer implements SpotifyPlayer.NotificationCallback,
			ConnectionStateCallback {
        public Player mPlayer;
        public boolean mLoggedIn = false;
        public String mPendingSong;

        public SpotPlayer(Context context) {
            SharedPreferences settings = PlaybackService.getSettings(context);
            String token = settings.getString(PrefKeys.SPOTIFY_TOKEN, null);
            if (token != null) {
                init(context, token);
            }
        }

        public void init(Context context, String token) {
            Config playerConfig = new Config(context, token, C.CLIENT_ID);
            Spotify.getPlayer(playerConfig, this, new SpotifyPlayer.InitializationObserver() {
                @Override
                public void onInitialized(SpotifyPlayer spotifyPlayer) {
                    mPlayer = spotifyPlayer;
                    mPlayer.addConnectionStateCallback(SpotPlayer.this);
                    mPlayer.addNotificationCallback(SpotPlayer.this);
                }

                @Override
                public void onError(Throwable throwable) {
                    Log.e(C.TAG, "Could not initialize player: " + throwable.getMessage());
                }
            });
        }

        @Override
        public void onPlaybackEvent(PlayerEvent playerEvent) {
            Log.d(C.TAG, "Playback event received: " + playerEvent.name());
            switch (playerEvent) {
                // Handle event type as necessary
                case kSpPlaybackNotifyTrackDelivered:
                    Log.d(C.TAG, "calling onCompletion()");
                    mCompletionListener.onCompletion(VanillaMediaPlayer.this);
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onPlaybackError(com.spotify.sdk.android.player.Error error) {
            Log.d(C.TAG, "Playback error received: " + error.name());
            switch (error) {
                // Handle error type as necessary
                case kSpErrorFailed:
                    mPendingSong = mDataSource;
                    SharedPreferences settings = PlaybackService.getSettings(mContext);
                    String token = settings.getString(PrefKeys.SPOTIFY_TOKEN, null);
                    if (token == null) {
                        throw new RuntimeException("spotify token is null");
                    }
                    mSP.mPlayer.login(token);
                default:
                    break;
            }
        }

        @Override
        public void onLoggedIn() {
            Log.d(C.TAG, "User logged in");
            mLoggedIn = true;
            if (mPendingSong != null) {
                mSP.mPlayer.playUri(null, mDataSource, 0, 0);
                mPendingSong = null;
            }
            //mPlayer.playUri(null, "spotify:track:2TpxZ7JUBn3uw46aR7qd6V", 0, 0);
        }

        @Override
        public void onLoggedOut() {
            mLoggedIn = false;
            Log.d(C.TAG, "User logged out");
        }

        @Override
        public void onLoginFailed(com.spotify.sdk.android.player.Error i) {
            Log.d(C.TAG, "Login failed: " + i);
        }

        @Override
        public void onTemporaryError() {
            Log.d(C.TAG, "Temporary error occurred");
        }

        @Override
        public void onConnectionMessage(String message) {
            Log.d(C.TAG, "Received connection message: " + message);
        }
    }

	/**
	 * Constructs a new VanillaMediaPlayer class
	 */
	public VanillaMediaPlayer(Context context) {
		super();
		mContext = context;
        mSP = new SpotPlayer(context);
	}

	/**
	 * Resets the media player to an unconfigured state
	 */
	public void reset() {
		mDataSource = null;
		mHasNextMediaPlayer = false;
        
		super.reset();
	}

	/**
	 * Releases the media player and frees any claimed AudioEffect
	 */
	public void release() {
        pause();
		mDataSource = null;
		mHasNextMediaPlayer = false;
        Spotify.destroyPlayer(mSP);
		super.release();
	}

	/**
	 * Sets the data source to use
	 */
	public void setDataSource(String path) throws IOException, IllegalArgumentException, SecurityException, IllegalStateException {
		// The MediaPlayer function expects a file:// like string but also accepts *most* absolute unix paths (= paths with no colon)
		// We could therefore encode the path into a full URI, but a much quicker way is to simply use
		// setDataSource(FileDescriptor) as the framework code would end up calling this function anyways (MediaPlayer.java:1100 (6.0))
        if (!path.contains("spotify:track:")) {
            FileInputStream fis = new FileInputStream(path);
            super.setDataSource(fis.getFD());
            fis.close(); // this is OK according to the SDK documentation!
        }
        mDataSource = path;
	}

	/**
	 * Returns the configured data source, may be null
	 */
	public String getDataSource() {
		return mDataSource;
	}

	/**
	 * Sets the next media player data source
	 */
	@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
	public void setNextMediaPlayer(VanillaMediaPlayer next) {
		super.setNextMediaPlayer(next);
		mHasNextMediaPlayer = (next != null);
	}

	/**
	 * Returns true if a 'next' media player has been configured
	 * via setNextMediaPlayer(next)
	 */
	public boolean hasNextMediaPlayer() {
		return mHasNextMediaPlayer;
	}

	/**
	 * Creates a new AudioEffect for our AudioSession
	 */
	public void openAudioFx() {
		Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
		i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, this.getAudioSessionId());
		i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, mContext.getPackageName());
		mContext.sendBroadcast(i);
	}

	/**
	 * Releases a previously claimed audio session id
	 */
	public void closeAudioFx() {
		Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
		i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, this.getAudioSessionId());
		i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, mContext.getPackageName());
		mContext.sendBroadcast(i);
	}

	/**
	 * Sets the desired scaling due to replay gain.
	 * @param replayGain the factor to adjust the volume by. Must be between 0 and 1 (inclusive)
	 *                    or {@link Float#NaN} to disable replay gain scaling
	 */
	public void setReplayGain(float replayGain) {
		mReplayGain = replayGain;
		updateVolume();
	}

	/**
	 * Sets whether we are ducking or not. Ducking is when we temporarily decrease the volume for
	 * a transient sound to play from another application, such as a notification's beep.
	 * @param isDucking true if we are ducking, false if we are not
	 */
	public void setIsDucking(boolean isDucking) {
		mIsDucking = isDucking;
		updateVolume();
	}

	/**
	 * Sets the desired scaling while ducking.
	 * @param duckingFactor the factor to adjust the volume by while ducking. Must be between 0
	 *                         and 1 (inclusive) or {@link Float#NaN} to disable ducking completely
	 *
	 * See also {@link #setIsDucking(boolean)}
	 */
	public void setDuckingFactor(float duckingFactor) {
		mDuckingFactor = duckingFactor;
		updateVolume();
	}
	/**
	 * Sets the volume, using the replay gain and ducking if appropriate
	 */
	private void updateVolume() {
		float volume = 1.0f;
		if (!Float.isNaN(mReplayGain)) {
			volume = mReplayGain;
		}
		if(mIsDucking && !Float.isNaN(mDuckingFactor)) {
			volume *= mDuckingFactor;
		}

		setVolume(volume, volume);
	}

    @Override
    public void prepare() throws IOException {
        if (!isSpotifyTrack()) {
            super.prepare();
        }
    }

    @Override
    public void start() {
        Log.d(C.TAG, "VanillaMediaPlayer.start()");
        if (isSpotifyTrack()) {
            Log.d(C.TAG, "starting spotify track");
            if (!mSP.mLoggedIn) {
                mSP.mPendingSong = mDataSource;
                SharedPreferences settings = PlaybackService.getSettings(mContext);
                String token = settings.getString(PrefKeys.SPOTIFY_TOKEN, null);
                if (token == null) {
                    throw new RuntimeException("spotify token is null");
                }
                mSP.mPlayer.login(token);
            } else {
                mSP.mPlayer.playUri(null, mDataSource, 0, 0);
            }
        } else {
            mSP.mPlayer.pause(null);
            super.start();
        }
    }

    public void resume() {
        Log.d(C.TAG, "VanillaMediaPlayer.resume()");
        if (isSpotifyTrack()) {
            mSP.mPlayer.resume(null);
        } else {
            super.start();
        }
    }

    @Override
    public void pause() {
        if (isSpotifyTrack()) {
            mSP.mPlayer.pause(null);
        } else {
            super.pause();
        }
    }

    @Override
    public int getDuration() {
        if (isSpotifyTrack()) {
            try {
                return (int)mSP.mPlayer.getMetadata().currentTrack.durationMs;
            } catch (NullPointerException e) {
                Log.e(C.TAG, "couldn't get duration:", e);
                return 0;
            }
        } else {
            return super.getDuration();
        }
    }

    @Override
    public int getCurrentPosition() {
        if (isSpotifyTrack()) {
            return (int)mSP.mPlayer.getPlaybackState().positionMs;
        } else {
            return super.getCurrentPosition();
        }
    }

    @Override
    public void seekTo(int pos) {
        if (isSpotifyTrack()) {
            mSP.mPlayer.seekToPosition(null, pos);
        } else {
            super.seekTo(pos);
        }
    }

    @Override
    public void setOnCompletionListener(
            MediaPlayer.OnCompletionListener listener) {
        mCompletionListener = listener;
        super.setOnCompletionListener(listener);
    }

    @Override
    public boolean isPlaying() {
        if (isSpotifyTrack()) {
            return mSP.mPlayer.getPlaybackState().isPlaying;
        } else {
            return super.isPlaying();
        }
    }

    @Override
    public void stop() {
        if (isSpotifyTrack()) {
            mSP.mPlayer.pause(null);
        } else {
            super.stop();
        }
    }

    private boolean isSpotifyTrack() {
        return (mDataSource != null) && mDataSource.contains("spotify:track:");
    }
}
