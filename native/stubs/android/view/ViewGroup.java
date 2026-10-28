package android.view;

/** Compile-time stub for child access and layout parameter fields. */
public class ViewGroup extends View {
    public int getChildCount() {return 0;}
    public View getChildAt(int index) {return null;}

    public static class LayoutParams {
        public int width, height;
    }

    public static class MarginLayoutParams extends LayoutParams {
        public int leftMargin, topMargin, rightMargin, bottomMargin;
    }
}
