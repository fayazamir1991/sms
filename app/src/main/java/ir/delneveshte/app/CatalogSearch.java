package ir.delneveshte.app;

import java.text.Normalizer;
import java.util.Locale;

/** Literal substring matching, tolerant of Persian spelling and spacing variants. */
public final class CatalogSearch {
    private CatalogSearch() { }
    public static String key(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .replaceAll("[\\u064b-\\u065f\\u0670]", "")
                .replace('ي', 'ی').replace('ك', 'ک')
                .replaceAll("[\\s\\u200c\\u200d\\u00a0]+", "").toLowerCase(Locale.ROOT);
    }
    public static boolean matches(String body, String query) { return key(body).contains(key(query)); }
}
