package chat.tags;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import android.view.View;
import android.view.ViewGroup;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Hot paths of the Chat tags plugin: profile height and position on every
 * scroll frame, search list padding and the tag buttons following the list.
 * Python keeps storage, matching, views, dialogs and settings.
 */
public final class TagsBridge {
    static final int HEIGHT = 0, POSITION = 1, PADDING = 2;

    /** Tag buttons above one search list; they scroll away with its first row. */
    static final class Search implements View.OnScrollChangeListener, View.OnLayoutChangeListener {
        final View root, list;
        int height;
        Search(View r, View l, int h) {root = r; list = l; height = h;}

        @Override
        public void onScrollChange(View v, int x, int y, int oldX, int oldY) {sync(this);}

        @Override
        public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {sync(this);}
    }

    private static volatile boolean active;
    private static final Map<Object, Object> profiles = Collections.synchronizedMap(new WeakHashMap<Object, Object>());
    private static final Map<Object, Search> searches = Collections.synchronizedMap(new WeakHashMap<Object, Search>());
    private static final ArrayList<XC_MethodHook.Unhook> handles = new ArrayList<>();
    private static final ArrayList<String> ids = new ArrayList<>();
    private static final Map<String, Field> fields = new HashMap<>();
    private static final Map<String, Method> methods = new HashMap<>();
    private static Method untrack;
    private static ClassLoader loader;
    private static int slot, actionsHeight, musicHeight;
    private static volatile String lastError;

    private TagsBridge() {}

    // ---- Lifecycle ------------------------------------------------------

    public static synchronized String[] install(Class<?> profile, Class<?> pager, String pluginId,
                                                int slotPx, int actionsPx, int musicPx) throws Exception {
        close();
        loader = profile.getClassLoader();
        slot = slotPx; actionsHeight = actionsPx; musicHeight = musicPx;
        Class<?> registry = Class.forName("app.exteraless.plugins.xposed.XposedHooks", true, loader);
        Method track = registry.getMethod("addPluginUnhook", String.class, XC_MethodHook.Unhook.class);
        untrack = registry.getMethod("unhook", String.class);
        // Resolve everything before the first callback can run.
        for (String n : new String[]{"actionsView", "extraHeight", "openAnimationInProgress",
                "playProfileAnimation", "hasMusic", "actionBar"}) field(profile, n);
        for (String n : new String[]{"pagesPaddingTop", "pagesPaddingBottom", "searchListView", "emptyView"})
            field(pager, n);
        try {
            add(profile, "getActionsExtraHeight", 1, HEIGHT, pluginId, track);
            add(profile, "updateActionsPosition", 0, POSITION, pluginId, track);
            add(pager, "setPagesPadding", 3, PADDING, pluginId, track);
            active = true;
            return ids.toArray(new String[0]);
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    public static synchronized void close() {
        active = false;
        for (XC_MethodHook.Unhook h : handles) try {h.unhook();} catch (Throwable ignored) {}
        handles.clear();
        if (untrack != null) for (String id : ids) try {untrack.invoke(null, id);} catch (Throwable ignored) {}
        ids.clear();
        profiles.clear();
        synchronized (searches) {
            for (Search s : searches.values()) unlisten(s);
            searches.clear();
        }
    }

    public static String lastError() {return lastError;}

    private static void add(Class<?> c, String name, int arity, int kind, String pid, Method track) throws Exception {
        Method target = null;
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == arity) {
                if (target != null) throw new IllegalStateException("Ambiguous method: " + name);
                target = m;
            }
        }
        if (target == null) throw new NoSuchMethodException(name);
        target.setAccessible(true);
        XC_MethodHook.Unhook h = XposedBridge.hookMethod(target, new Callback(kind));
        if (h == null) throw new IllegalStateException("Hook failed: " + name);
        handles.add(h);
        Object id = track.invoke(null, pid, h);
        if (id == null) throw new IllegalStateException("Untracked hook: " + name);
        ids.add(id.toString());
    }

    // ---- State pushed from Python ---------------------------------------

    public static void attachProfile(Object profile, Object view) {profiles.put(profile, view);}

    public static void detachProfile(Object profile) {profiles.remove(profile);}

    /** Called on the UI thread whenever the tag buttons are rendered. */
    public static void setSearch(Object pager, Object view, int height) {
        if (!active) return;
        try {
            Search s = searches.get(pager);
            if (s == null || s.root != view) {
                if (s != null) unlisten(s);
                View list = (View) get(pager, "searchListView");
                s = new Search((View) view, list, height);
                list.setOnScrollChangeListener(s);
                list.addOnLayoutChangeListener(s);
                searches.put(pager, s);
            }
            s.height = height;
            applyPadding(pager);
        } catch (Throwable e) {
            report(e);
        }
    }

    public static void detachSearch(Object pager) {
        Search s = searches.remove(pager);
        if (s != null) unlisten(s);
    }

    public static void reposition(Object profile) {
        if (!active) return;
        try {position(profile);} catch (Throwable e) {report(e);}
    }

    private static void unlisten(Search s) {
        s.list.setOnScrollChangeListener(null);
        s.list.removeOnLayoutChangeListener(s);
        s.root.setTranslationY(0f);
    }

    // ---- Callbacks ------------------------------------------------------

    private static final class Callback extends XC_MethodHook {
        private final int kind;
        private boolean failed;
        Callback(int k) {super(50); kind = k;}

        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            if (!active || failed || p.getThrowable() != null) return;
            try {
                switch (kind) {
                    case HEIGHT: height(p); break;
                    case POSITION: position(p.thisObject); break;
                    case PADDING: applyPadding(p.thisObject); break;
                    default: break;
                }
            } catch (Throwable e) {
                failed = true;
                report(e);
            }
        }
    }

    private static void report(Throwable e) {
        while (e.getCause() != null && e instanceof java.lang.reflect.InvocationTargetException) e = e.getCause();
        lastError = e.toString();
        // The client blocks XposedBridge.log for plugin code; android.util.Log is allowed.
        try {
            Class.forName("android.util.Log").getMethod("e", String.class, String.class, Throwable.class)
                    .invoke(null, "ChatTags", "TagsBridge callback disabled", e);
        } catch (Throwable ignored) {}
    }

    private static void height(XC_MethodHook.MethodHookParam p) throws Exception {
        if (((Number) call(p.thisObject, "getDialogId")).longValue() != 0)
            p.setResult(((Number) p.getResult()).intValue() + slot);
    }

    private static int actionBarTop(Object profile) throws Exception {
        Object bar = get(profile, "actionBar");
        int top = ((Number) staticCall(bar.getClass(), "getCurrentActionBarHeight")).intValue();
        if ((Boolean) call(bar, "getOccupyStatusBar"))
            top += ((Number) staticField("org.telegram.messenger.AndroidUtilities", "statusBarHeight")).intValue();
        return top;
    }

    private static boolean galleryOpening(Object profile) throws Exception {
        return (Boolean) get(profile, "openAnimationInProgress")
                && ((Number) get(profile, "playProfileAnimation")).intValue() == 2;
    }

    /** Buttons are lifted by the slot; the tag block fills the header bottom. */
    static void position(Object profile) throws Exception {
        Object root = profiles.get(profile);
        if (root == null) return;
        Object actions = get(profile, "actionsView");
        int top = actionBarTop(profile);
        float headerBottom = ((Number) get(profile, "extraHeight")).floatValue() + top;
        int music = (Boolean) get(profile, "hasMusic") ? musicHeight : 0;
        boolean opening = (Boolean) get(profile, "openAnimationInProgress");
        boolean gallery = galleryOpening(profile);
        float y, alpha = 1f;
        if (actions != null) {
            if (gallery) {
                y = ((View) actions).getTranslationY() + actionsHeight + music;
            } else {
                float bottom = headerBottom - music - slot;
                float height = Math.min(actionsHeight, bottom - top);
                exact(actions.getClass(), "updatePosition", float.class, float.class)
                        .invoke(actions, bottom - height, height);
                y = headerBottom - slot;
            }
            alpha = ((View) actions).getAlpha();
        } else {
            y = headerBottom - slot;
        }
        View view = (View) root;
        view.setTranslationY(y);
        boolean visible = y >= top - dp(1) && !opening;
        view.setAlpha(visible ? alpha : 0f);
        view.setVisibility(visible ? 0 : 4);
    }

    static void applyPadding(Object pager) throws Exception {
        Search s = searches.get(pager);
        if (s == null) return;
        int top = ((Number) get(pager, "pagesPaddingTop")).intValue();
        int bottom = ((Number) get(pager, "pagesPaddingBottom")).intValue();
        View list = s.list;
        exact(list.getClass(), "setPadding", int.class, int.class, int.class, int.class, boolean.class)
                .invoke(list, list.getPaddingLeft(), top + s.height, list.getPaddingRight(), bottom, false);
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) s.root.getLayoutParams();
        if (params.topMargin != top || params.height != s.height) {
            params.topMargin = top;
            params.height = s.height;
            s.root.setLayoutParams(params);
        }
        View empty = (View) get(pager, "emptyView");
        if (empty != null) {
            ViewGroup.MarginLayoutParams ep = (ViewGroup.MarginLayoutParams) empty.getLayoutParams();
            if (ep.topMargin != top + s.height) {
                ep.topMargin = top + s.height;
                empty.setLayoutParams(ep);
            }
        }
        s.root.bringToFront();
        sync(s);
    }

    /**
     * The buttons sit in the list's top padding (the list does not clip to it),
     * so they move by exactly as much as the first row has scrolled.
     */
    static float offset(boolean firstRowShown, int firstTop, int listPaddingTop, int hiddenShift) {
        if (!firstRowShown) return -hiddenShift;
        return Math.min(0, firstTop - listPaddingTop);
    }

    static void sync(Search s) {
        if (!active) return;
        try {
            ViewGroup list = (ViewGroup) s.list;
            float shift = 0f;
            if (list.getChildCount() > 0) {
                View first = list.getChildAt(0);
                int position = ((Number) exact(list.getClass(), "getChildAdapterPosition", View.class)
                        .invoke(list, first)).intValue();
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) s.root.getLayoutParams();
                shift = offset(position == 0, first.getTop(), list.getPaddingTop(), params.topMargin + s.height);
            }
            if (s.root.getTranslationY() != shift) s.root.setTranslationY(shift);
        } catch (Throwable e) {
            report(e);
        }
    }

    // ---- Cached reflection ----------------------------------------------

    private static int dp(float value) throws Exception {
        return ((Number) staticCall(cls("org.telegram.messenger.AndroidUtilities"), "dp", value)).intValue();
    }

    private static final Map<String, Class<?>> classes = new HashMap<>();

    private static Class<?> cls(String name) throws ClassNotFoundException {
        synchronized (classes) {
            Class<?> c = classes.get(name);
            if (c == null) {
                c = Class.forName(name, false, loader != null ? loader : TagsBridge.class.getClassLoader());
                classes.put(name, c);
            }
            return c;
        }
    }

    static Field field(Class<?> start, String name) throws NoSuchFieldException {
        String key = start.getName() + "#" + name;
        synchronized (fields) {
            Field f = fields.get(key);
            if (f != null) return f;
            for (Class<?> c = start; c != null; c = c.getSuperclass()) {
                try {
                    f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    fields.put(key, f);
                    return f;
                } catch (NoSuchFieldException ignored) {}
            }
        }
        throw new NoSuchFieldException(start.getName() + "." + name);
    }

    private static Object get(Object obj, String name) throws Exception {return field(obj.getClass(), name).get(obj);}

    private static Object staticField(String className, String name) throws Exception {
        return field(cls(className), name).get(null);
    }

    private static Method method(Class<?> start, String name, Object[] args) throws NoSuchMethodException {
        String key = start.getName() + "#" + name + "/" + args.length;
        synchronized (methods) {
            Method m = methods.get(key);
            if (m != null) return m;
            for (Class<?> c = start; c != null; c = c.getSuperclass()) {
                Method[] declared;
                try {
                    declared = c.getDeclaredMethods();
                } catch (LinkageError hidden) {
                    continue; // framework classes can declare methods with hidden-API types
                }
                for (Method x : declared) {
                    if (x.getName().equals(name) && accepts(x.getParameterTypes(), args)) {
                        x.setAccessible(true);
                        methods.put(key, x);
                        return x;
                    }
                }
            }
        }
        throw new NoSuchMethodException(start.getName() + "." + name + Arrays.toString(args));
    }

    private static boolean accepts(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = wrap(types[i]);
            if (args[i] != null && !t.isInstance(args[i])) return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> t) {
        if (!t.isPrimitive()) return t;
        if (t == int.class) return Integer.class;
        if (t == long.class) return Long.class;
        if (t == float.class) return Float.class;
        if (t == boolean.class) return Boolean.class;
        if (t == double.class) return Double.class;
        return t;
    }

    /** Exact signature lookup up the hierarchy; never enumerates framework classes. */
    private static Method exact(Class<?> start, String name, Class<?>... types) throws NoSuchMethodException {
        String key = start.getName() + "#" + name + Arrays.toString(types);
        synchronized (methods) {
            Method m = methods.get(key);
            if (m != null) return m;
            for (Class<?> c = start; c != null; c = c.getSuperclass()) {
                try {
                    m = c.getDeclaredMethod(name, types);
                    m.setAccessible(true);
                    methods.put(key, m);
                    return m;
                } catch (NoSuchMethodException ignored) {}
            }
        }
        throw new NoSuchMethodException(start.getName() + "." + name);
    }

    private static Object call(Object obj, String name, Object... args) throws Exception {
        return method(obj.getClass(), name, args).invoke(obj, args);
    }

    private static Object staticCall(Class<?> c, String name, Object... args) throws Exception {
        return method(c, name, args).invoke(null, args);
    }
}
