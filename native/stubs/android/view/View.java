package android.view;

/** Compile-time stub: only the members TagsBridge calls. The device supplies the real class. */
public class View {
    public interface OnScrollChangeListener {
        void onScrollChange(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY);
    }

    public interface OnLayoutChangeListener {
        void onLayoutChange(View v, int left, int top, int right, int bottom,
                            int oldLeft, int oldTop, int oldRight, int oldBottom);
    }

    public float getTranslationY() {return 0;}
    public void setTranslationY(float y) {}
    public float getAlpha() {return 1;}
    public void setAlpha(float a) {}
    public void setVisibility(int v) {}
    public int getTop() {return 0;}
    public int getPaddingTop() {return 0;}
    public int getPaddingLeft() {return 0;}
    public int getPaddingRight() {return 0;}
    public ViewGroup.LayoutParams getLayoutParams() {return null;}
    public void setLayoutParams(ViewGroup.LayoutParams p) {}
    public void bringToFront() {}
    public void setOnScrollChangeListener(OnScrollChangeListener l) {}
    public void addOnLayoutChangeListener(OnLayoutChangeListener l) {}
    public void removeOnLayoutChangeListener(OnLayoutChangeListener l) {}
    public ViewTreeObserver getViewTreeObserver() {return null;}
}
