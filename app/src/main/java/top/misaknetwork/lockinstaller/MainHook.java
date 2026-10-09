package top.misaknetwork.lockinstaller;

import android.content.Intent;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class MainHook extends XposedModule {

    private static final String TAG = "LockInstaller";
    private static final String TARGET_INSTALLER = "com.rosan.installer.x.revived";
    private static final String[] SYSTEM_INSTALLERS = {
            "com.android.packageinstaller",
            "com.oplus.appdetail",
            "com.google.android.packageinstaller"
    };

    // 只在 system_server 启动时注入
    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        log(TAG + ": 系统框架已启动，开始注入直捣黄龙 Hook...");
        ClassLoader classLoader = param.getClassLoader();

        try {
            // Android 10+ 统一使用 ActivityStarter.execute
            Class<?> activityStarterClass = classLoader.loadClass("com.android.server.wm.ActivityStarter");
            Method executeMethod = activityStarterClass.getDeclaredMethod("execute");

            // 使用 102 API 的 Hook 方式
            hook(executeMethod).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        // 1. 获取当前 ActivityStarter 对象
                        Object thisObject = chain.getThisObject();
                        
                        // 2. 反射获取 mRequest 字段
                        Field mRequestField = findField(thisObject.getClass(), "mRequest");
                        if (mRequestField == null) return chain.proceed();
                        
                        Object mRequest = mRequestField.get(thisObject);
                        if (mRequest == null) return chain.proceed();

                        // 3. 反射获取 mRequest 中的 intent 字段
                        Field intentField = findField(mRequest.getClass(), "intent");
                        if (intentField == null) return chain.proceed();

                        Intent intent = (Intent) intentField.get(mRequest);
                        if (intent == null) return chain.proceed();

                        // 4. 核心判断逻辑：是否要劫持？
                        boolean shouldRedirect = false;

                        // 显式指定了系统安装器
                        if (intent.getComponent() != null) {
                            String pkg = intent.getComponent().getPackageName();
                            for (String sysPkg : SYSTEM_INSTALLERS) {
                                if (sysPkg.equals(pkg)) {
                                    shouldRedirect = true;
                                    break;
                                }
                            }
                        }

                        // 隐式 Intent：APK 安装请求
                        boolean isImplicitApk = "android.intent.action.VIEW".equals(intent.getAction())
                                && "application/vnd.android.package-archive".equals(intent.getType());
                        boolean isInstallAction = "android.intent.action.INSTALL_PACKAGE".equals(intent.getAction());

                        if (shouldRedirect || isImplicitApk || isInstallAction) {
                            log(TAG + ": 捕获到安装请求，原目标: " + intent.getComponent() + ", 准备劫持...");
                            forceRedirect(intent);
                        }

                    } catch (Throwable t) {
                        log(TAG + ": Hook 报错 -> " + t.getMessage());
                    }
                    
                    // 继续执行原有逻辑
                    return chain.proceed();
                }
            });

            log(TAG + ": Hook ActivityStarter.execute 成功！");

        } catch (Throwable t) {
            log(TAG + ": 找不到 ActivityStarter, 可能被 ART 内联了。尝试备选方案 -> " + t.getMessage());
        }
    }

    // 重定向逻辑
    private void forceRedirect(Intent intent) {
        // 1. 剥离系统安装器，清除 Package 限制
        intent.setComponent(null);
        intent.setPackage(null);

        // 2. 强制指定第三方安装器 (rosan)
        intent.setPackage(TARGET_INSTALLER);

        // 3. 修正 Action (第三方安装器通常认 ACTION_VIEW)
        if ("android.intent.action.INSTALL_PACKAGE".equals(intent.getAction())) {
            intent.setAction("android.intent.action.VIEW");
        }

        // 4. 赋予必要的 Flag
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        log(TAG + ": 劫持完成，强制指向 -> " + TARGET_INSTALLER);
    }

    // 向上查找字段的辅助方法（解决混淆和继承问题）
    private Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    // 日志辅助
    private void log(String msg) {
        // 也可以直接使用 android.util.Log.e("LSPosed_DirectHook", msg);
        log(msg); // 使用 102 API 自带的 log 方法
    }
}