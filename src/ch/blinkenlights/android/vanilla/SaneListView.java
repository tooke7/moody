package ch.blinkenlights.android.vanilla;

import android.content.Context;
import android.widget.ListView;
import android.util.AttributeSet;
import android.content.res.Resources;
import android.view.MotionEvent;
import android.util.Log;

public class SaneListView extends ListView {

	private static final int EDGE_ALLOW_DP = 8;
	private static final int EDGE_PROTECT_DP = 50;

	private float mEdgeAllowPx = 0;
	private float mEdgeProtectPx = 0;

	public SaneListView(Context context) {
		super(context);
		selfInit();
	}
	public SaneListView(Context context, AttributeSet attrs) {
		super(context, attrs);
		selfInit();
	}
	public SaneListView(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		selfInit();
	}
	public SaneListView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);
		selfInit();
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent ev) {
		if (ev.getX() > getWidth()-mEdgeProtectPx && ev.getX() < getWidth()-mEdgeAllowPx) {
			Log.v("VanillaMusic", "Performing a FAKE EVENT");
			ev = MotionEvent.obtain(ev.getDownTime(), ev.getEventTime(), ev.getAction(), getWidth()-mEdgeProtectPx, ev.getY(), ev.getMetaState());
		}
		Log.v("VanillaMusic", "E: "+ev);
		return super.onInterceptTouchEvent(ev);
	}


	public void selfInit() {
		mEdgeAllowPx = (EDGE_ALLOW_DP * Resources.getSystem().getDisplayMetrics().density);
		mEdgeProtectPx = (EDGE_PROTECT_DP * Resources.getSystem().getDisplayMetrics().density);
	}

}
