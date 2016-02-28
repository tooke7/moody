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

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnTouchListener;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import android.util.Log;


public class SlidingView extends FrameLayout
	implements View.OnTouchListener
	{
	/**
	 * Our current offset
	 */
	private float mViewOffsetY = 0;
	/**
	 * The maximum (initial) offset of the view
	 */
	private float mMaxOffsetY = 0;
	/**
	 *
	 */
	private float mPreviousY = 0;
	private float mProgressPx = 0;
	private boolean mDidScroll = false;
	private final float MAX_PROGRESS = 30;


	public SlidingView(Context context) {
		this(context, null);
	}

	public SlidingView(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public SlidingView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	/**
	 * Called after the view was inflated, binds an onTouchListener to all child
	 * elements of the child view
	 */
	@Override
	protected void onFinishInflate() {
		super.onFinishInflate();

		int childCount = getChildCount();
		for (int i=0; i < childCount; i++) {
			View child = getChildAt(i);
			if (child instanceof ViewGroup) {
				ViewGroup group = (ViewGroup)child;
				int gchildren = group.getChildCount();
				for (int g = 0; g < gchildren ; g++) {
					group.getChildAt(g).setOnTouchListener(this);
					Log.v("VanillaMusic", "Bound listener to child at "+ group.getChildAt(g));
				}
				// We bound to all children of our first child
				break;
			}
		}
	}


	/**
	 * Attempts to stack all views orizontally in the available space
	 */
	@Override
	protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		super.onLayout(changed, left, top, right, bottom);

		int viewHeight = getMeasuredHeight();
		int childCount = getChildCount();
		int topOffset = 0;
		View lastChild = null;

		for (int i = 0; i < childCount ; i++) {
			lastChild = getChildAt(i);
			int childWidth = lastChild.getMeasuredWidth();
			int childHeight = lastChild.getMeasuredHeight();
			int childBottom = childHeight + topOffset;

			// No child should consume space outside of our view
			if (topOffset > viewHeight)
				topOffset = viewHeight;
			if (childBottom > viewHeight)
				childBottom = viewHeight;

Log.v("VanillaMusic", "Stacked child "+i+" at "+topOffset +" up to "+childBottom);
			lastChild.layout(0, topOffset, childWidth, childBottom);
			topOffset += childHeight;
		}

		if (lastChild != null && mMaxOffsetY == 0) {
			mMaxOffsetY = lastChild.getHeight();
			mViewOffsetY = mMaxOffsetY;
			setTranslationY(mViewOffsetY);
		}
	}


	@Override
	public boolean onTouch(View v, MotionEvent event){
		float y = event.getRawY();
		float dy = y - mPreviousY;

//Log.v("VanillaMusic", "DY: "+dy);
		switch(event.getActionMasked()) {
			case MotionEvent.ACTION_UP : {
//Log.v("VanillaMusic", "Progress was: "+mProgressPx);
				if (mDidScroll == false) { // Dispatch event if we never scrolled
					v.onTouchEvent(event);
				} else 	if(mViewOffsetY < (mMaxOffsetY/2)) {
					this.animate().translationY(0);
				} else {
					this.animate().translationY(mMaxOffsetY);
				}

				break;
			}
			case MotionEvent.ACTION_DOWN : {
				v.onTouchEvent(event);

				mViewOffsetY = getTranslationY();
				mProgressPx = 0;
				mDidScroll = false;

				break;
			}
			case MotionEvent.ACTION_MOVE : {
				mViewOffsetY += dy;
				mProgressPx += Math.abs(dy);

				float usedY = mViewOffsetY;
				if (usedY < 0)
					usedY = 0;
				if (usedY > mMaxOffsetY)
					usedY = mMaxOffsetY;

				if (mProgressPx < MAX_PROGRESS) {
					// we did not reach a minimum of progress: do not scroll yet
					usedY = getTranslationY();
				} else {
					if (mDidScroll == false) {
						event.setAction(MotionEvent.ACTION_CANCEL);
						v.onTouchEvent(event);
					}
					mDidScroll = true;
				}

				setTranslationY(usedY);
//Log.v("VanillaMusic", "Setting it to "+usedY);
				break;
			}
		}
		mPreviousY = y;
		return true;
	}


}
