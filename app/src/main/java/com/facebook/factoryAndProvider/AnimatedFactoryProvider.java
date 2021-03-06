package com.facebook.factoryAndProvider;


import com.facebook.bitmapFactory.PlatformBitmapFactory;
import com.facebook.executor.executorSupplier.DefaultExecutorSupplier;
import com.facebook.executor.executorSupplier.ExecutorSupplier;
import com.facebook.factoryAndProvider.animatedFactory.AnimatedFactory;
import com.facebook.factoryAndProvider.animatedFactory.AnimatedFactoryImpl;

import java.lang.reflect.Constructor;

/**
 * Created by heshixiyang on 2017/3/16.
 */
/**
 * 用于提供一个{@link AnimatedFactory}的默认实现{@link AnimatedFactoryImpl}
 */
public class AnimatedFactoryProvider {

    private static boolean sImplLoaded;

    private static AnimatedFactory sImpl = null;

    /**
     * 这里的{@link AnimatedFactoryImpl}是单例，如果没有被加载，那么就通过反射加载它
     * @param platformBitmapFactory 这个参数用来提供在不同系统版本下创建Bitmap的方式
     * @param executorSupplier 这个参数是用来提供各种Executor的，该参数的默认实现是{@link DefaultExecutorSupplier}
     * @return 返回一个{@link AnimatedFactoryImpl}
     */
    public static AnimatedFactory getAnimatedFactory(
            PlatformBitmapFactory platformBitmapFactory,
            ExecutorSupplier executorSupplier) {
        if (!sImplLoaded) {
            try {
                final Class<?> clazz =
                        Class.forName("com.facebook.factoryAndProvider.animatedFactory.animatedDrawableFactory.AnimatedFactoryImplSupport");
                final Constructor<?> constructor = clazz.getConstructor(
                        PlatformBitmapFactory.class,
                        ExecutorSupplier.class);
                sImpl = (AnimatedFactory) constructor.newInstance(
                        platformBitmapFactory,
                        executorSupplier);
            } catch (Throwable e) {
                // Head in the sand
            }
            if (sImpl != null) {
                sImplLoaded = true;
                return sImpl;
            }
            try {
                final Class<?> clazz =
                        Class.forName("com.facebook.factoryAndProvider.animatedFactory.AnimatedFactoryImpl");
                final Constructor<?> constructor = clazz.getConstructor(
                        PlatformBitmapFactory.class,
                        ExecutorSupplier.class);
                sImpl = (AnimatedFactory) constructor.newInstance(
                        platformBitmapFactory,
                        executorSupplier);
            } catch (Throwable e) {
                // Head in the sand
            }
            sImplLoaded = true;
        }
        return sImpl;
    }

}
