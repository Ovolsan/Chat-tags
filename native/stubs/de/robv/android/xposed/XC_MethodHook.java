
package de.robv.android.xposed;
import java.lang.reflect.Member;
public abstract class XC_MethodHook {
 public XC_MethodHook(int priority) {}
 protected void beforeHookedMethod(MethodHookParam p) throws Throwable {}
 protected void afterHookedMethod(MethodHookParam p) throws Throwable {}
 public static class MethodHookParam {
  public Object thisObject; public Object[] args; public Member method;
  private Object result; public boolean returnEarly;
  public Throwable throwable;
  public Throwable getThrowable(){return throwable;}
  public Object getResult(){return result;}
  public void setResult(Object r){result=r;returnEarly=true;}
  public void setOriginal(Object r){result=r;}
 }
 public class Unhook {
  Runnable cleanup;
  public void unhook(){if(cleanup!=null)cleanup.run();}
 }
}
