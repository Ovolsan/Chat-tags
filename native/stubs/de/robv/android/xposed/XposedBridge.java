
package de.robv.android.xposed;
import java.lang.reflect.*;
import java.util.*;
public final class XposedBridge {
 public static final Map<Member,XC_MethodHook> hooks=new HashMap<>();
 public static int errors;
 public static XC_MethodHook.Unhook hookMethod(final Member m,XC_MethodHook h){
  hooks.put(m,h);XC_MethodHook.Unhook u=h.new Unhook();
  u.cleanup=()->hooks.remove(m);return u;
 }
 public static void log(Throwable t){errors++;t.printStackTrace();}
 public static Object call(Object obj,String name,Object...args)throws Throwable {
  Method m=null;
  for(Method x:obj.getClass().getDeclaredMethods())
   if(x.getName().equals(name)&&x.getParameterTypes().length==args.length){m=x;break;}
  if(m==null)throw new NoSuchMethodException(name);
  m.setAccessible(true);
  XC_MethodHook h=hooks.get(m);XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();
  p.thisObject=obj;p.method=m;p.args=args;
  if(h!=null)h.beforeHookedMethod(p);
  Throwable failure=null;
  try{if(!p.returnEarly)p.setOriginal(m.invoke(obj,p.args));}
  catch(InvocationTargetException e){failure=e.getCause();}
  finally{if(h!=null)h.afterHookedMethod(p);}
  if(failure!=null)throw failure;
  return p.getResult();
 }
}
