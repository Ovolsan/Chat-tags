package android.view;

/** Compile-time stub for the pre-draw callback. */
public final class ViewTreeObserver {
    public interface OnPreDrawListener {
        boolean onPreDraw();
    }

    public boolean isAlive() {return true;}
    public void addOnPreDrawListener(OnPreDrawListener l) {}
    public void removeOnPreDrawListener(OnPreDrawListener l) {}
}
