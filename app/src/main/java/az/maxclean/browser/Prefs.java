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
    public static final String BRIDGES = "bridges";
    public static final String BRIDGE_LINES = "bridge_lines";
    /** The proxy chain before Tor (or for the browser without Tor): one address per line. */
    public static final String PROXY_URL = "proxy_url";
    /** One proxy after Tor, for sites that block Tor. */
    public static final String PROXY_AFTER = "proxy_after";
    public static final String FP_PROTECT = "fp_protect";
    public static final String DEVICE_PROFILE = "device_profile";
    // Full edition: look and feel
    public static final String THEME = "theme";
    public static final String ACCENT = "accent";
    public static final String ICON = "icon";
    public static final String ANIMATIONS = "animations";
    public static final String FAKE_GEO = "fake_geo";
    public static final String FAKE_GEO_CUSTOM = "fake_geo_custom";

    /** Values of {@link #BRIDGES}. */
    public static final String BRIDGES_NONE = "none";
    public static final String BRIDGES_SNOWFLAKE = "snowflake";
    public static final String BRIDGES_OBFS4 = "obfs4";
    public static final String BRIDGES_CUSTOM = "custom";

    private Prefs() {}

    public static String str(Context c, String key, String def) {
        return get(c).getString(key, def).trim();
    }

    /**
     * Everything that decides how traffic leaves the phone. When it changes, Tor or the proxy
     * has to be set up again.
     */
    public static String networkSignature(Context c) {
        return on(c, TOR) + "|" + str(c, BRIDGES, BRIDGES_NONE) + "|" + str(c, BRIDGE_LINES, "")
                + "|" + str(c, PROXY_URL, "") + "|" + str(c, PROXY_AFTER, "");
    }

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static boolean on(Context c, String key) {
        return get(c).getBoolean(key, defaultOn(key));
    }

    public static boolean defaultOn(String key) {
        switch (key) {
            case THIRD_PARTY_COOKIES:
            case USER_SCRIPT_ON:
                return false;
            default:
                return true;
        }
    }

}
