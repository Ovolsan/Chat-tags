
package app.exteraless.plugins.xposed;
import de.robv.android.xposed.XC_MethodHook;
import java.util.*;
public final class XposedHooks {
 public static int failAt=-1;
 public static Map<String,XC_MethodHook.Unhook> tracked=new HashMap<>();
 public static String addPluginUnhook(String p,XC_MethodHook.Unhook h){
  if(tracked.size()==failAt)throw new IllegalStateException("registration rejected");
  String id=UUID.randomUUID().toString();tracked.put(id,h);return id;
 }
 public static void unhook(String id){XC_MethodHook.Unhook h=tracked.remove(id);if(h!=null)h.unhook();}
}
