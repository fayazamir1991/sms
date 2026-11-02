package ir.delneveshte.app;

/** The same display eligibility rule is used before pagination and before rendering. */
public final class CatalogText {
    private CatalogText() { }
    public static String render(String raw,String nickname) {
        if(raw==null)return null;
        String name=nickname==null?"":nickname.trim();if(name.isEmpty())name="عزیزم";
        String text=raw.split("\\n—",2)[0].trim().replace("{name}",name);
        if(text.isEmpty()||text.length()>1200||text.matches("(?si).*(?:https?:|www\\.|[a-z0-9-]+\\.(?:com|ir|net|org)\\b).*"))return null;
        if(text.matches("(?s).*[A-Za-z]{3,}.*")||!text.matches("(?s).*[\\u0600-\\u06ff].*"))return null;
        return text;
    }
}
