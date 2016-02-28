/*
 * Copyright (C) 2016 Adrian Ulrich <adrian@blinkenlights.ch>
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

package ch.blinkenlights.android.vanilla;

import android.annotation.SuppressLint;
import android.app.Fragment;
import android.content.Context;
import android.os.Bundle;
import android.widget.AdapterView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import com.mobeta.android.dslv.DragSortListView;

import android.util.Log;

public class ShowQueueFragment extends Fragment
	implements TimelineCallback,
	           AdapterView.OnItemClickListener,
	           DragSortListView.DropListener,
	           DragSortListView.RemoveListener
	{

	private DragSortListView mListView;
	private ShowQueueAdapter mListAdapter;
	private PlaybackService mService;

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		super.onCreateView(inflater, container, savedInstanceState);

		View view = inflater.inflate(R.layout.showqueue_listview, container, false);
		Context context = getActivity();

		view.setBackgroundColor(ThemeHelper.getDefaultCoverColors(getActivity())[0]);

		mListView    = (DragSortListView) view.findViewById(R.id.list);
		mListAdapter = new ShowQueueAdapter(context, R.layout.draggable_row);
		mListView.setAdapter(mListAdapter);
		mListView.setDropListener(this);
		mListView.setRemoveListener(this);
		mListView.setOnItemClickListener(this);

		PlaybackService.addTimelineCallback(this);
		return view;
	}

	@Override
	public void onDestroyView() {
	Log.v("VanillaMusic", "Fragment destroy");
		PlaybackService.removeTimelineCallback(this);
		super.onDestroyView();
	}

	@Override
	public void onResume() {
		super.onResume();
		
		mService = PlaybackService.get(getActivity()); // fixme
		refreshSongQueueList(true);
	}

	/**
	 * Fired from adapter listview  if user moved an item
	 * @param from the item index that was dragged
	 * @param to the index where the item was dropped
	 */
	@Override
	public void drop(int from, int to) {
		if (from != to) {
			mService.moveSongPosition(from, to);
		}
	}

	/**
	 * Fired from adapter listview after user removed a song
	 * @param which index to remove from queue
	 */
	@Override
	public void remove(int which) {
		mService.removeSongPosition(which);
	}

	/**
	 * Called when an item in the listview gets clicked
	 */
	@Override
	public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
			mService.jumpToQueuePosition(position);
	}

	/**
	 * Triggers a refresh of the queueview
	 * @param scroll enable or disable jumping to the currently playing item
	 */
	public void refreshSongQueueList(final boolean scroll) {
		getActivity().runOnUiThread(new Runnable(){
			public void run() {
				int i, stotal, spos;
				stotal = mService.getTimelineLength();   /* Total number of songs in queue */
				spos   = mService.getTimelinePosition(); /* Current position in queue      */

				mListAdapter.clear();                    /* Flush all existing entries...  */
				mListAdapter.highlightRow(spos);         /* and highlight current position */

				for(i=0 ; i<stotal; i++) {
					mListAdapter.add(mService.getSongByQueuePosition(i));
				}

				if(scroll)
					scrollToCurrentSong(spos);
			}
		});
	}

	/**
	 * Scrolls to the current song<br/>
	 * We suppress the new api lint check as lint thinks
	 * {@link android.widget.AbsListView#setSelectionFromTop(int, int)} was only added in
	 * {@link Build.VERSION_CODES#JELLY_BEAN}, but it was actually added in API
	 * level 1<br/>
	 * <a href="https://developer.android.com/reference/android/widget/AbsListView.html#setSelectionFromTop%28int,%20int%29">
	 *     Android reference: AbsListView.setSelectionFromTop()</a>
	 * @param currentSongPosition The position in {@link #mListView} of the current song
	 */
	@SuppressLint("NewApi")
	private void scrollToCurrentSong(int currentSongPosition){
		mListView.setSelectionFromTop(currentSongPosition, 0); /* scroll to currently playing song */
	}

	// Used Callbacks of TImelineCallback
	public void onTimelineChanged() {
		Log.v("VanillaMusic", "The timeline has been changed!");
		refreshSongQueueList(false);
	}

	// Unused Callbacks of TimelineCallback
	public void onPositionInfoChanged() {
	}
	public void onMediaChange() {
	}
	public void recreate() {
	}
	public void replaceSong(int delta, Song song) {
	}
	public void setSong(long uptime, Song song) {
	}
	public void setState(long uptime, int state) {
	}
}
