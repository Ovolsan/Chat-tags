package chat.tags;

import java.util.List;

/** JVM checks for the matching rules shared with chat_tags.plugin. */
public final class TagsBridgeTest {
    private static int checks;

    private static void eq(Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        eq("дача", TagsBridge.normalize("  #ДАЧА  "));
        eq("отчеты", TagsBridge.normalize("Отчёты"));
        eq("поставщик плитки", TagsBridge.normalize("Поставщик\u00a0  плитки"));
        eq("ab", TagsBridge.normalize("a\u200bb"));
        eq("", TagsBridge.normalize(" # "));
        eq(0, TagsBridge.rank("дача", "дача"));
        eq(1, TagsBridge.rank("дач", "дача"));
        eq(2, TagsBridge.rank("галтер", "бухгалтерия"));
        eq(3, TagsBridge.rank("пицц дост", "доставка пиццы"));
        eq(-1, TagsBridge.rank("fishing", "рыбалка"));
        eq(-1, TagsBridge.rank("", "рыбалка"));
        TagsBridge.setIndex(100, new long[]{-8, -7, 5}, new String[]{"Второй", "Рыболовы", "Иван"},
                new String[]{"рыбалка\nозеро", "рыболов", "бухгалтерия"});
        TagsBridge.setIndex(200, new long[]{-9}, new String[]{"Чужой"}, new String[]{"рыбалка"});
        List<TagsBridge.Match> found = TagsBridge.matches(100, "Рыб");
        eq(2, found.size());
        eq(-8L, found.get(0).entry.did);
        eq(-7L, found.get(1).entry.did);
        eq(0, TagsBridge.matches(100, "рыболов").get(0).rank);
        eq(1, TagsBridge.matches(200, "рыбалка").size());
        eq(0, TagsBridge.matches(300, "рыбалка").size());
        eq(0, TagsBridge.matches(100, "  ").size());
        System.out.println("TagsBridge checks: " + checks);
    }
}
