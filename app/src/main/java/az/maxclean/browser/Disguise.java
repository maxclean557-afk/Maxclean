package az.maxclean.browser;

import android.content.Context;

import java.util.Locale;

/**
 * What the browser tells sites about the phone and its location.
 *
 * Device profiles are whole, consistent sets of values taken from real phones. Random values
 * would make the browser rarer and so easier to track; a common, consistent profile hides it in
 * a crowd. The User-Agent stays the reduced one every Chrome on Android sends ("Android 10; K").
 */
public final class Disguise {
    private Disguise() {}

    /** {value, label, hint} for the settings screen. */
    public static final String[][] PROFILES = {
            {"generic", "Обычный", "Как у большинства телефонов: 4 ядра, 4 ГБ, без модели"},
            {"samsung", "Samsung Galaxy S23", "Экран 360×780, 8 ядер, 8 ГБ"},
            {"pixel", "Google Pixel 8", "Экран 412×915, 8 ядер, 8 ГБ"},
            {"xiaomi", "Redmi Note 10 Pro", "Экран 393×873, 8 ядер, 6 ГБ"},
    };

    /** {value, label, latitude, longitude}. */
    public static final String[][] PLACES = {
            {"off", "Не сообщать сайтам", "", ""},
            {"moscow", "Москва", "55.7558", "37.6173"},
            {"amsterdam", "Амстердам", "52.3676", "4.9041"},
            {"berlin", "Берлин", "52.5200", "13.4050"},
            {"newyork", "Нью-Йорк", "40.7128", "-74.0060"},
            {"tokyo", "Токио", "35.6762", "139.6503"},
            {"custom", "Свои координаты", "", ""},
    };

    /** JavaScript object literal for assets/fingerprint.js, or "null" for the generic values. */
    public static String profileJs(Context c) {
        switch (Prefs.str(c, Prefs.DEVICE_PROFILE, "generic")) {
            case "samsung":
                return "{model:'SM-S911B',android:'14.0.0',w:360,h:780,dpr:3,cores:8,mem:8}";
            case "pixel":
                return "{model:'Pixel 8',android:'14.0.0',w:412,h:915,dpr:2.625,cores:8,mem:8}";
            case "xiaomi":
                return "{model:'M2101K6G',android:'13.0.0',w:393,h:873,dpr:2.75,cores:8,mem:6}";
            default:
                return "null";
        }
    }

    /** "[lat, lon]" for the fake location, or null when sites get no location at all. */
    public static double[] place(Context c) {
        String key = Prefs.str(c, Prefs.FAKE_GEO, "off");
        if (key.equals("custom")) {
            String[] p = Prefs.str(c, Prefs.FAKE_GEO_CUSTOM, "").replace(" ", "").split(",");
            try {
                double lat = Double.parseDouble(p[0]), lon = Double.parseDouble(p[1]);
                if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180) return new double[]{lat, lon};
            } catch (RuntimeException ignored) {
                // Not two numbers: no location.
            }
            return null;
        }
        for (String[] p : PLACES) {
            if (p[0].equals(key) && !p[2].isEmpty()) {
                return new double[]{Double.parseDouble(p[2]), Double.parseDouble(p[3])};
            }
        }
        return null;
    }

    /** Page script that answers location requests with the chosen place. */
    public static String geoJs(Context c) {
        double[] p = place(c);
        if (p == null) return "";
        String pos = String.format(Locale.US,
                "{coords:{latitude:%.5f,longitude:%.5f,accuracy:25,altitude:null,altitudeAccuracy:null,"
                        + "heading:null,speed:null},timestamp:Date.now()}", p[0], p[1]);
        return "try{var mcPos=function(){return " + pos + ";};var mcGeo={"
                + "getCurrentPosition:function(ok){setTimeout(function(){ok(mcPos());},400);},"
                + "watchPosition:function(ok){setTimeout(function(){ok(mcPos());},400);return 1;},"
                + "clearWatch:function(){}};"
                + "Object.defineProperty(Navigator.prototype,'geolocation',{get:function(){return mcGeo;},configurable:true});"
                + "}catch(e){}";
    }
}
