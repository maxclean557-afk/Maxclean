package az.maxclean.browser;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User settings only. Nothing about browsing (history, cookies, sites) is ever stored here.
 */
public final class Prefs {
    public static final String TOR = "tor";
    public static final String BLOCK_ADS = "block_ads";
    public static final String BLOCK_TRACKERS = "block_trackers";
    public static final String BLOCK_SOCIAL = "block_social";
    public static final String BLOCK_MINERS = "block_miners";
    public static final String BLOCK_POPUPS = "block_popups";
    public static final String HIDE_AD_BLOCKS = "hide_ad_blocks";
    public static final String HIDE_COOKIE_BANNERS = "hide_cookie_banners";
    public static final String JAVASCRIPT = "javascript";
    public static final String COOKIES = "cookies";
    public static final String THIRD_PARTY_COOKIES = "third_party_cookies";
    public static final String DISABLE_WEBRTC = "disable_webrtc";
    public static final String GENERIC_UA = "generic_ua";
    public static final String NO_SCREENSHOTS = "no_screenshots";
    public static final String HTTPS_ONLY = "https_only";
    public static final String GPC = "gpc";
    public static final String BLOCK_AUTOPLAY = "block_autoplay";
    public static final String USER_SCRIPT_ON = "user_script_on";
    public static final String USER_JS = "user_js";
    public static final String USER_CSS = "user_css";
    public static final String ENGINE = "engine";
    public static final String CUSTOM_ENGINE = "custom_engine";
    public static final String USER_BLOCKLIST = "user_blocklist";

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static boolean on(Context c, String key) {
        return get(c).getBoolean(key, defaultOn(key));
    }

    public static boolean defaultOn(String key) {
        switch (key) {
            case TOR:
            case THIRD_PARTY_COOKIES:
            case USER_SCRIPT_ON:
                return false;
            default:
                return true;
        }
    }

}
