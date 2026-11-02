package ir.delneveshte.app;

import java.text.Normalizer;
import java.util.Locale;

/** Literal substring matching, tolerant of Persian spelling and spacing variants. */
public final class CatalogSearch {
    private static final java.util.regex.Pattern MARKS=java.util.regex.Pattern.compile("[\\u064b-\\u065f\\u0670]");
    private static final java.util.regex.Pattern SPACES=java.util.regex.Pattern.compile("[\\s\\u200c\\u200d\\u00a0]+");
    private CatalogSearch() { }
    public static String key(String text) {
        return SPACES.matcher(MARKS.matcher(Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)).replaceAll("")
                .replace('ي', 'ی').replace('ك', 'ک')
                ).replaceAll("").toLowerCase(Locale.ROOT);
    }
    public static boolean canSearch(String query){return key(query).codePoints().filter(Character::isLetterOrDigit).limit(2).count()>=2;}
    public static boolean matches(String body, String query) { return key(body).contains(key(query)); }
}
