package chat.tags;

/** JVM checks for the scroll rule of the tag buttons above the search list. */
public final class TagsBridgeTest {
    private static int checks;

    private static void eq(float expected, float actual) {
        checks++;
        if (expected != actual) throw new AssertionError("expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        // List at rest: the first row starts right below the buttons.
        eq(0f, TagsBridge.offset(true, 300, 300, 400));
        // Scrolled by 120 px: the buttons move up by the same amount.
        eq(-120f, TagsBridge.offset(true, 180, 300, 400));
        // Overscroll or item animation below the padding never pushes them down.
        eq(0f, TagsBridge.offset(true, 330, 300, 400));
        // First row gone: the buttons are moved fully above the list.
        eq(-400f, TagsBridge.offset(false, 0, 300, 400));
        System.out.println("TagsBridge checks: " + checks);
    }
}
