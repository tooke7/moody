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

import android.view.Menu;
import android.view.MenuItem;

public class SlidingPlaybackActivity extends PlaybackActivity
	implements SlidingView.Callback
{
	private Menu mMenu;
	protected SlidingView mSlidingView;

	@Override
	protected void bindControlButtons() {
		super.bindControlButtons();

		mSlidingView = (SlidingView)findViewById(R.id.sliding_view);
		mSlidingView.setCallback(this);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		mMenu = menu;
		super.onCreateOptionsMenu(menu);
		onSlideFullyExpanded(false);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case MENU_SHOW_QUEUE:
			mSlidingView.expandSlide();
			break;
		case MENU_HIDE_QUEUE:
			mSlidingView.hideSlide();
			break;
		default:
			return super.onOptionsItemSelected(item);
		}
		return true;
	}

	/**
	 * Called by SlidingView to signal a visibility change.
	 * Toggles the visibility of menu items
	 *
	 * @param expanded true if slide fully expanded
	 */
	@Override
	public void onSlideFullyExpanded(boolean expanded) {
		if (mMenu == null)
			return; // not initialized yet

		final int[] slide_visible = {MENU_HIDE_QUEUE, MENU_CLEAR_QUEUE, MENU_EMPTY_QUEUE, MENU_SAVE_QUEUE_AS_PLAYLIST};
		final int[] slide_hidden = {MENU_SHOW_QUEUE, MENU_SORT, MENU_DELETE, MENU_ENQUEUE_ALBUM, MENU_ENQUEUE_ARTIST, MENU_ENQUEUE_GENRE};

		for (int id : slide_visible) {
			MenuItem item = mMenu.findItem(id);
			if (item != null)
				item.setVisible(expanded);
		}

		for (int id : slide_hidden) {
			MenuItem item = mMenu.findItem(id);
			if (item != null)
				item.setVisible(!expanded);
		}
	}

}
