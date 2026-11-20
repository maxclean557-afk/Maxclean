package az.maxclean.browser;

import android.content.Context;
import android.webkit.WebView;

/**
 * The WebView of a tab. With "Фоновая активность вкладок" on, it never tells Chromium that its
 * window was hidden: Chromium pauses video with sound (YouTube and the like) as soon as the window
 * goes to the background, whatever the page itself thinks. Switching tabs still hides a tab as usual
 * (that is the view's own visibility, not the window's).
 */
final class TabView extends WebView {
    TabView(Context c) {
        super(c);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        if (visibility != VISIBLE && Prefs.on(getContext(), Prefs.KEEP_VISIBLE)) {
            super.onWindowVisibilityChanged(VISIBLE);
            return;
        }
        super.onWindowVisibilityChanged(visibility);
    }
}
