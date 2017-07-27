package com.jacobobryant.moody.vanilla;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.jacobobryant.moody.C;
import com.spotify.sdk.android.player.ConnectionStateCallback;
import com.spotify.sdk.android.player.Player;
import com.spotify.sdk.android.player.PlayerEvent;
import com.spotify.sdk.android.player.SpotifyPlayer;

public class SpotifyActivity extends Activity implements
    SpotifyPlayer.NotificationCallback, ConnectionStateCallback
{
    private static final String CLIENT_ID = "3b35f98db202408688eeca3e75090342";
    private static final String REDIRECT_URI = "smartshuffle://spotifycallback";

    private Player mPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_spotify);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onPlaybackEvent(PlayerEvent playerEvent) {
        Log.d(C.TAG, "Playback event received: " + playerEvent.name());
        switch (playerEvent) {
            // Handle event type as necessary
            default:
                break;
        }
    }

    @Override
    public void onPlaybackError(com.spotify.sdk.android.player.Error error) {
        Log.d(C.TAG, "Playback error received: " + error.name());
        switch (error) {
            // Handle error type as necessary
            default:
                break;
        }
    }

    @Override
    public void onLoggedIn() {
        Log.d(C.TAG, "User logged in");
    }

    @Override
    public void onLoggedOut() {
        Log.d(C.TAG, "User logged out");
    }

    @Override
    public void onLoginFailed(com.spotify.sdk.android.player.Error i) {
        Log.d(C.TAG, "Login failed");
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
