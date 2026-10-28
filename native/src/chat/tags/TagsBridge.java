package chat.tags;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import android.view.View;
import android.view.ViewGroup;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Hot paths of the Chat tags plugin: profile height and position on every
 * scroll frame, localSearch on the storage queue and search list padding.
 * Python keeps storage, views, dialogs and settings and pushes snapshots here.
 */
public final class TagsBridge {
    static final int HEIGHT = 0, POSITION = 1, SEARCH = 2, PADDING = 3;

    /** Immutable per-account index: pre-normalized tags from Python. */
    static final class Entry {
        final long did; final String title, sortTitle; final String[] tags;
        Entry(long d, String t, String[] g) {did = d; title = t; sortTitle = normalize(t); tags = g;}
    }
    static final class Match {
        final Entry entry; final int rank;
        Match(Entry e, int r) {entry = e; rank = r;}
    }
    static final class Search {
        final Object view; final int height;
        Search(Object v, int h) {view = v; height = h;}
    }

    private static volatile boolean active;
    private static volatile Map<Long, Entry[]> index = Collections.emptyMap();
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

    public static synchronized String[] install(Class<?> profile, Class<?> storage, Class<?> pager,
                                                String pluginId, int slotPx, int actionsPx, int musicPx)
            throws Exception {
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
        field(storage.getSuperclass(), "currentAccount");
        try {
            add(profile, "getActionsExtraHeight", 1, HEIGHT, pluginId, track);
            add(profile, "updateActionsPosition", 0, POSITION, pluginId, track);
            add(storage, "localSearch", 7, SEARCH, pluginId, track);
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
        searches.clear();
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

    /** tags[i] holds the normalized tags of dids[i], separated by '\n'. */
    public static void setIndex(long uid, long[] dids, String[] titles, String[] tags) {
        Entry[] entries = new Entry[dids.length];
        for (int i = 0; i < dids.length; i++)
            entries[i] = new Entry(dids[i], titles[i], tags[i].isEmpty() ? new String[0] : tags[i].split("\n"));
        HashMap<Long, Entry[]> next = new HashMap<>(index);
        next.put(uid, entries);
        index = next;
    }

    public static void attachProfile(Object profile, Object view) {profiles.put(profile, view);}

    public static void detachProfile(Object profile) {profiles.remove(profile);}

    public static void setSearch(Object pager, Object view, int height) {
        searches.put(pager, new Search(view, height));
        try {applyPadding(pager);} catch (Throwable e) {report(e);}
    }

    public static void detachSearch(Object pager) {searches.remove(pager);}

    public static void reposition(Object profile) {
        if (!active) return;
        try {position(profile);} catch (Throwable e) {report(e);}
    }

    // ---- Matching (mirrors clean_tag / normalized / tag_rank in Python) --

    static String normalize(String value) {
        String s = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int type = Character.getType(c);
            if (type != Character.FORMAT && type != Character.SURROGATE) b.append(c);
        }
        // Manual whitespace handling: Android's ICU regex rejects (?U).
        String t = collapse(b.toString());
        int start = 0;
        while (start < t.length() && t.charAt(start) == '#') start++;
        return collapse(t.substring(start)).toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private static String collapse(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean space = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                space = out.length() > 0;
            } else {
                if (space) out.append(' ');
                out.append(c);
                space = false;
            }
        }
        return out.toString();
    }

    static int rank(String query, String tag) {
        if (query.isEmpty()) return -1;
        if (tag.equals(query)) return 0;
        if (tag.startsWith(query)) return 1;
        if (tag.contains(query)) return 2;
        for (String word : query.split(" ")) if (!tag.contains(word)) return -1;
        return 3;
    }

    static List<Match> matches(long uid, String rawQuery) {
        String query = normalize(rawQuery);
        Entry[] entries = index.get(uid);
        ArrayList<Match> out = new ArrayList<>();
        if (query.isEmpty() || entries == null) return out;
        for (Entry e : entries) {
            int best = -1;
            for (String tag : e.tags) {
                int r = rank(query, tag);
                if (r >= 0 && (best < 0 || r < best)) best = r;
            }
            if (best >= 0) out.add(new Match(e, best));
        }
        Collections.sort(out, (a, b) -> {
            if (a.rank != b.rank) return a.rank - b.rank;
            int c = a.entry.sortTitle.compareTo(b.entry.sortTitle);
            return c != 0 ? c : Long.compare(a.entry.did, b.entry.did);
        });
        return out;
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
                    case SEARCH: search(p); break;
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

    @SuppressWarnings("unchecked")
    private static void search(XC_MethodHook.MethodHookParam p) throws Exception {
        if (((Number) p.args[0]).intValue() != 0) return; // peer pickers keep their own rules
        Object storage = p.thisObject;
        int account = ((Number) get(storage, "currentAccount")).intValue();
        Class<?> config = cls("org.telegram.messenger.UserConfig");
        long uid = ((Number) call(staticCall(config, "getInstance", account), "getClientUserId")).longValue();
        List<Match> found = matches(uid, String.valueOf(p.args[1]));
        if (found.isEmpty()) return;
        List<Object> results = (List<Object>) p.args[2];
        List<Object> names = (List<Object>) p.args[3];
        List<Object> encUsers = (List<Object>) p.args[4];
        List<?> allowed = (List<?>) p.args[5];
        Set<Long> allowedIds = null;
        if (allowed != null) {
            allowedIds = new HashSet<>();
            for (Object v : allowed) allowedIds.add(((Number) v).longValue());
        }
        Set<Long> existing = new HashSet<>();
        for (Object obj : results) existing.add(objectId(obj));
        Class<?> dialogs = cls("org.telegram.messenger.DialogObject");
        Object controller = staticCall(cls("org.telegram.messenger.MessagesController"), "getInstance", account);
        Class<?> userObject = cls("org.telegram.messenger.UserObject");
        Class<?> tlUser = cls("org.telegram.tgnet.TLRPC$User");
        Class<?> tlChat = cls("org.telegram.tgnet.TLRPC$Chat");
        // Pairs are appended together so ProfileSearchCell receives aligned arrays.
        for (Match m : found) {
            long did = m.entry.did;
            if (existing.contains(did) || (allowedIds != null && !allowedIds.contains(did))) continue;
            Object obj, encUser = null;
            if ((Boolean) staticCall(dialogs, "isEncryptedDialog", did)) {
                int encId = ((Number) staticCall(dialogs, "getEncryptedChatId", did)).intValue();
                obj = call(controller, "getEncryptedChat", Integer.valueOf(encId));
                if (obj == null) obj = call(storage, "getEncryptedChat", (long) encId);
                if (obj == null) continue;
                long userId = ((Number) get(obj, "user_id")).longValue();
                encUser = call(controller, "getUser", Long.valueOf(userId));
                if (encUser == null) encUser = call(storage, "getUser", userId);
                if (encUser == null) continue;
            } else if (did < 0) {
                obj = call(controller, "getChat", Long.valueOf(-did));
                if (obj == null) obj = call(storage, "getChat", -did);
            } else {
                obj = call(controller, "getUser", Long.valueOf(did));
                if (obj == null) obj = call(storage, "getUser", did);
            }
            if (obj == null || (tlUser.isInstance(obj) && (Boolean) get(obj, "deleted"))) continue;
            String title;
            if (tlChat.isInstance(obj)) {
                Object t = get(obj, "title");
                title = t != null && !t.toString().isEmpty() ? t.toString() : m.entry.title;
            } else {
                title = String.valueOf(staticCall(userObject, "getUserName", encUser != null ? encUser : obj));
            }
            results.add(obj);
            names.add(title);
            if (encUser != null) {
                // ProfileSearchCell resolves encrypted chats from this cache.
                call(controller, "putEncryptedChat", obj, true);
                encUsers.add(encUser);
            }
            existing.add(did);
        }
    }

    private static long objectId(Object obj) throws Exception {
        if (obj == null) return 0;
        if (cls("org.telegram.tgnet.TLRPC$User").isInstance(obj)) return ((Number) get(obj, "id")).longValue();
        if (cls("org.telegram.tgnet.TLRPC$Chat").isInstance(obj)) return -((Number) get(obj, "id")).longValue();
        if (cls("org.telegram.tgnet.TLRPC$EncryptedChat").isInstance(obj))
            return 0x4000000000000000L | (((Number) get(obj, "id")).longValue() & 0xffffffffL);
        return 0;
    }

    static void applyPadding(Object pager) throws Exception {
        Search s = searches.get(pager);
        if (s == null) return;
        int top = ((Number) get(pager, "pagesPaddingTop")).intValue();
        int bottom = ((Number) get(pager, "pagesPaddingBottom")).intValue();
        View list = (View) get(pager, "searchListView");
        exact(list.getClass(), "setPadding", int.class, int.class, int.class, int.class, boolean.class)
                .invoke(list, list.getPaddingLeft(), top + s.height, list.getPaddingRight(), bottom, false);
        View root = (View) s.view;
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) root.getLayoutParams();
        if (params.topMargin != top || params.height != s.height) {
            params.topMargin = top;
            params.height = s.height;
            root.setLayoutParams(params);
        }
        View empty = (View) get(pager, "emptyView");
        if (empty != null) {
            ViewGroup.MarginLayoutParams ep = (ViewGroup.MarginLayoutParams) empty.getLayoutParams();
            if (ep.topMargin != top + s.height) {
                ep.topMargin = top + s.height;
                empty.setLayoutParams(ep);
            }
        }
        root.bringToFront();
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

    private static void set(Object obj, String name, Object value) throws Exception {field(obj.getClass(), name).set(obj, value);}

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
