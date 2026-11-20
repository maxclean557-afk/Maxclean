package az.maxclean.browser;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * Home screen search bar: tap it to open Maxclean ready to type, or "+" for a new tab.
 * It shows nothing from your browsing; it only opens the browser.
 */
public class SearchWidget extends AppWidgetProvider {
    static final String ACTION_SEARCH = "az.maxclean.browser.WIDGET_SEARCH";
    static final String ACTION_NEW_TAB = "az.maxclean.browser.WIDGET_NEW_TAB";

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_search);
        v.setOnClickPendingIntent(R.id.widget_root, open(c, ACTION_SEARCH, 0));
        v.setOnClickPendingIntent(R.id.widget_search, open(c, ACTION_SEARCH, 0));
        v.setOnClickPendingIntent(R.id.widget_new_tab, open(c, ACTION_NEW_TAB, 1));
        m.updateAppWidget(ids, v);
    }

    private static PendingIntent open(Context c, String action, int code) {
        Intent i = new Intent(c, MainActivity.class).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
