package android.view;

/** Compile-time stub for layout parameter fields. */
public class ViewGroup extends View {
    public static class LayoutParams {
        public int width, height;
    }

    public static class MarginLayoutParams extends LayoutParams {
        public int leftMargin, topMargin, rightMargin, bottomMargin;
    }
}
