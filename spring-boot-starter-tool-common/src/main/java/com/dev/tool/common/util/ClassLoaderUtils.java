package com.dev.tool.common.util;

import com.dev.tool.common.model.DevToolClassLoader;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;

public class ClassLoaderUtils {

    private static Map<GroupToolEnum, ClassLoader> classLoaderMap = new HashMap<>();


    public static < T extends ClassLoader> T getClassLoader(GroupToolEnum groupToolEnum) {
        return (T)classLoaderMap.get(groupToolEnum);
    }

    public static ClassLoader initAndGetDevToolClassLoader(GroupToolEnum groupToolEnum, String classPath,ClassLoader parent) {
        if (null == groupToolEnum) {
            throw new RuntimeException("工具组枚举不可以为空");
        }
        String classFilePath = EnvUtil.getDataActualFilePath(groupToolEnum, classPath);
        if (null == classFilePath) {
            throw new RuntimeException("类文件路径不可以为空");
        }
        synchronized (DevToolClassLoader.class) {
            classLoaderMap.put(groupToolEnum, new DevToolClassLoader(classFilePath,parent));
        }
        return classLoaderMap.get(groupToolEnum);
    }

    public static ClassLoader initAndGetURLClassLoader(GroupToolEnum groupToolEnum, URL[] urls,ClassLoader parent) {
        if (null == groupToolEnum) {
            throw new RuntimeException("工具组枚举不可以为空");
        }
        if (null == urls) {
            throw new RuntimeException("urls不可以为空");
        }
        synchronized (DevToolClassLoader.class) {
            URLClassLoader urlClassLoader = new URLClassLoader(urls,parent);
            URLClassLoader oldURLClassLoader = (URLClassLoader)classLoaderMap.put(groupToolEnum, urlClassLoader);
            if(null != oldURLClassLoader){
                try {
                    oldURLClassLoader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return classLoaderMap.get(groupToolEnum);
    }
}
