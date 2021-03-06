package com.dev.tool.rmi.dubbo.processor;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.dev.tool.common.model.ClassLoadFromConfig;
import com.dev.tool.common.model.Event;
import com.dev.tool.common.model.InterfaceInfo;
import com.dev.tool.common.model.JarFileLoadInfo;
import com.dev.tool.common.model.MethodInfo;
import com.dev.tool.common.model.Result;
import com.dev.tool.common.processor.AbstractClassSensitiveProcessor;
import com.dev.tool.common.util.BeanFactoryUtils;
import com.dev.tool.common.util.CacheUtils;
import com.dev.tool.common.util.ClassLoadFromEnum;
import com.dev.tool.common.util.ClassUtils;
import com.dev.tool.common.util.EnvUtil;
import com.dev.tool.common.util.GroupEnum;
import com.dev.tool.common.util.GroupToolEnum;
import com.dev.tool.common.util.LockUtils;
import com.dev.tool.common.util.RarUtils;
import com.dev.tool.common.util.ReflectUtils;
import com.dev.tool.common.util.ResultUtils;
import com.dev.tool.rmi.dubbo.model.DubboInvokeConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.utils.ReferenceConfigCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 工具主页控制器
 */
public class DubboToolProcessor extends AbstractClassSensitiveProcessor {

    private Logger logger = LoggerFactory.getLogger(DubboToolProcessor.class);

    private static final String JAR_DIR = "jars";
    private static final String LOAD_CONFIG = "load_config";

    //返回已加载好的数据
    @Override
    public Result pageLoad(Event event) {
        try {
            Map<String,Object> data = new HashMap<>();
            data.put("jarInfos",new ArrayList<>(CacheUtils.getRmiJarInfoHashMap().values().stream().map(l -> l.getJarName()).sorted(Comparator.naturalOrder()).collect(Collectors.toList())));
            data.put("loadConfig",EnvUtil.getConfig(GroupToolEnum.DUBBO,LOAD_CONFIG,HashMap.class));
            return ResultUtils.successResult(data);
        } catch (Exception e) {
            logger.error("加载接口，读取方法异常", e);
            return ResultUtils.errorResult("加载接口，读取方法异常," + e.toString());
        }
    }

    @Override
    public Result dataLoad(Event event) {
        switch (event.getEventSource()) {
            case "saveJarPath":
                return saveJarPath(event.getEventData());
            case "saveConfig":
                return saveConfig(event.getEventData());
            case "jarName":
                return ResultUtils.successResult(new ArrayList(CacheUtils.getRmiJarInfoHashMap().get(event.getEventData().get("jarName")).getInterfaceInfoMap().keySet()));
            case "interfaceClassName":
                return ResultUtils.successResult(CacheUtils.getRmiJarInfoHashMap().get(event.getEventData().get("jarName"))
                        .getInterfaceInfoMap().get(event.getEventData().get("interfaceClassName")));
            case "methodName":
                return ResultUtils.successResult(CacheUtils.getRmiJarInfoHashMap().get(event.getEventData().get("jarName"))
                        .getInterfaceInfoMap().get(event.getEventData().get("interfaceClassName"))
                        .getMethodInfoMap().get(event.getEventData().get("methodName")).getParameters());
            case "invoke":
                return invoke(event);
        }
        return ResultUtils.errorResult("不支持的eventSource:" + event.getEventSource());
    }

    public Result invoke(Event event) {
        DubboInvokeConfig dubboInvokeConfig = JSONObject.parseObject(event.getEventData().get("param"), DubboInvokeConfig.class);
        return dubboInvoke(dubboInvokeConfig);
    }

    public Result dubboInvoke(DubboInvokeConfig dubboInvokeConfig) {
        if (null == dubboInvokeConfig || null == dubboInvokeConfig.getInterfaceClassName() || null == dubboInvokeConfig.getMethodName() || null == dubboInvokeConfig.getParams() || null == dubboInvokeConfig.getVersion()) {
            return ResultUtils.errorResult("入参非法");
        }

        //缓存service的version和group
        InterfaceInfo info = CacheUtils.getRmiJarInfoHashMap().get(dubboInvokeConfig.getJarName()).getInterfaceInfoMap().get(dubboInvokeConfig.getInterfaceClassName());
        info.setGroup(dubboInvokeConfig.getGroup());
        info.setVersion(dubboInvokeConfig.getVersion());
        try {
            return ResultUtils.successResult(invoke(dubboInvokeConfig));
        } catch (Exception e) {
            logger.error("调用异常", e);
            return ResultUtils.errorResult("调用异常," + e.toString());
        }
    }

    /**
     * 保存jar路径
     * @param param
     * @return
     */
    public Result saveJarPath(Map<String, String> param) {
        try {
            if (!LockUtils.tryLock(GroupEnum.RMI)) {
                return ResultUtils.errorResult("正在并发操作中,请稍等");
            }
            Map<String, String> loadConfigMap = EnvUtil.getConfig(GroupToolEnum.DUBBO, LOAD_CONFIG, HashMap.class);
            if (null == loadConfigMap || loadConfigMap.size() == 0) {
                return ResultUtils.errorResult("jar解析规则配置为空，请先设置解析规则配置");
            }
            String jarPath = param.get("jarPath");
            //1.解析jar包、并且移动jar包到jars目录，且判断重复jar包信息，高版本覆盖低版本
            JarFileLoadInfo jarFileLoadInfo = new JarFileLoadInfo(GroupToolEnum.DUBBO);
            RarUtils.parseAndInitJarFile(Arrays.asList(jarPath), jarFileLoadInfo, JAR_DIR, loadConfigMap);
            return ResultUtils.successResult(true);
        } catch (Exception e) {
            logger.error("加载接口，读取方法异常", e);
            return ResultUtils.errorResult("加载接口，读取方法异常," + e.toString());
        } finally {
            LockUtils.unLock(GroupEnum.RMI);
        }
    }

    /**
     * 保存解析配置
     * @param param
     * @return
     */
    public Result saveConfig(Map<String, String> param) {
        try {
            if (!LockUtils.tryLock(GroupEnum.RMI)) {
                return ResultUtils.errorResult("正在并发操作中，请稍等");
            }
            String artifactIdIncludeRulePattern = param.get("artifactIdIncludeRulePattern");
            String artifactIdExcludeRulePattern = param.get("artifactIdExcludeRulePattern");
            String classRulePattern = param.get("classRulePattern");
            if (StringUtils.isEmpty(artifactIdIncludeRulePattern)) {
                return ResultUtils.errorResult("jar包解析配置不能为空");
            }

            if (StringUtils.isEmpty(classRulePattern)) {
                return ResultUtils.errorResult("类包名正则表达式配置不能为空");
            }

            try {
                Pattern.compile(artifactIdIncludeRulePattern);
            } catch (Exception e) {
                return ResultUtils.errorResult("include正则表达式异常," + e.toString());
            }

            try {
                if(!StringUtils.isEmpty("artifactIdExcludeRulePattern")){
                    Pattern.compile(artifactIdExcludeRulePattern);
                }
            } catch (Exception e) {
                return ResultUtils.errorResult("exclude正则表达式异常," + e.toString());
            }

            try {
                Pattern.compile(classRulePattern);
            } catch (Exception e) {
                return ResultUtils.errorResult("类包名正则表达式异常," + e.toString());
            }

            Map<String, String> configMap = new HashMap<>();
            configMap.put("artifactIdIncludeRulePattern", artifactIdIncludeRulePattern);
            configMap.put("artifactIdExcludeRulePattern", artifactIdExcludeRulePattern);
            configMap.put("classRulePattern", classRulePattern);
            EnvUtil.updateConfig(GroupToolEnum.DUBBO, LOAD_CONFIG, configMap, true);
            return ResultUtils.successResult(true);
        } catch (Exception e) {
            logger.error("加载接口，读取方法异常", e);
            return ResultUtils.errorResult("加载接口，读取方法异常," + e.toString());
        } finally {
            LockUtils.unLock(GroupEnum.RMI);
        }
    }

    @Override
    public Result refresh(Event event) {
        try {
            //1.获取加载规则配置
            Map<String, String> loadConfigMap = EnvUtil.getConfig(GroupToolEnum.DUBBO, LOAD_CONFIG, HashMap.class);
            if (null == loadConfigMap) {
                if (null == event) {
                    return ResultUtils.successResult(null);
                }
                return ResultUtils.errorResult("jar解析规则配置为空，请先设置解析规则配置");
            }

            //2.获取jar包配置
            JarFileLoadInfo jarFileLoadInfo = new JarFileLoadInfo(GroupToolEnum.DUBBO);
            RarUtils.loadRemainedJarArtifact(jarFileLoadInfo, JAR_DIR);

            //3.按规则加载jar的class
            ClassUtils.loadJarClassIntoJvm(jarFileLoadInfo, loadConfigMap);
            //4.重置map缓存
            CacheUtils.destoryRmiJarInfoHashMap();
            jarFileLoadInfo.getFinalLoadedJarInfos().stream().forEach(l -> {
                CacheUtils.getRmiJarInfoHashMap().put(l.getJarName(), l);
            });
            //5.摧毁dubbo相关缓存
            destoryCache();

            return ResultUtils.successResult(jarFileLoadInfo.getFinalLoadedJarInfos().stream().map(l -> l.getJarName()).collect(Collectors.toList()));
        } catch (Exception e) {
            logger.error("加载接口，读取方法异常", e);
            return ResultUtils.errorResult("加载接口，读取方法异常," + e.toString());
        }
    }


    /**
     * 调用dubbo接口
     *
     * @param config
     * @return
     */
    public Object invoke(DubboInvokeConfig config) throws Exception {
        InterfaceInfo interfaceInfo = CacheUtils.getRmiJarInfoHashMap().get(config.getJarName()).getInterfaceInfoMap().get(config.getInterfaceClassName());
        if (null == interfaceInfo) {
            return "未找到接口：" + config.getInterfaceClassName();
        }
        ReferenceConfig referenceConfig = BeanFactoryUtils.getBean("referenceConfig", ReferenceConfig.class);
        referenceConfig.setInterface(interfaceInfo.getInterfaceClazz());
        referenceConfig.setGroup(config.getGroup());
        referenceConfig.setVersion(config.getVersion());

        Object service = ReferenceConfigCache.getCache().get(referenceConfig);
//            Object service = referenceConfig.get();
        MethodInfo methodInfo = interfaceInfo.getMethodInfoMap().get(config.getMethodName());
        Object obj = remoteInvoke(service, methodInfo.getMethod(), config.getParams());
        return obj;
    }


    private Object remoteInvoke(Object service, Method callMethod, String[] paramValues) throws Exception {
        if (callMethod.getParameterTypes().length > 0) {
            Object[] params = new Object[callMethod.getParameterTypes().length];
            for (int i = 0, j = callMethod.getParameterTypes().length; i < j; i++) {
                //返回的是java.util.List,可以通过这个判断是否集合
                Class t = callMethod.getParameterTypes()[i];
                String s = paramValues[i];
                //数组或者list
                if (t.isAssignableFrom(List.class) || t.isArray()) {
                    //返回的是带有泛型类型的参数格式,可以取到泛型类型  java.util.List<java.util.Long>
                    ParameterizedType p = (ParameterizedType) callMethod.getGenericParameterTypes()[i];
                    //这里需要强制将Type转为Class，因为json没有提供单个Type的方法
                    List list = JSONArray.parseArray(s, (Class) p.getActualTypeArguments()[0]);
                    params[i] = list;
                    if (t.isArray()) {
                        params[i] = list.toArray();
                    }
                } else {
                    params[i] = JSONObject.parseObject(s, t);
                }
            }
            return callMethod.invoke(service, params);
        } else {
            return callMethod.invoke(service);
        }
    }

    @Override
    public Result before(Event event) {
        return ResultUtils.successResult(null);
    }

    @Override
    public Result finish(Event event, Result result) {
        return result;
    }

    @Override
    public GroupToolEnum matchGroupToolEnum() {
        return GroupToolEnum.DUBBO;
    }

    @Override
    public ClassLoadFromConfig classLoadFromConfig() {
        return new ClassLoadFromConfig(ClassLoadFromEnum.LOAD_FROM_JAR, new URL[0]);
    }

    private void destoryCache() throws Exception {
        //移除dubbo缓存
        ReferenceConfigCache.getCache().destroyAll();
        ReflectUtils.modifyField("CACHE_HOLDER", ReferenceConfigCache.class,new ConcurrentHashMap<String, ReferenceConfigCache>());
        //移除ReflectUtils的缓存
        //移除class的缓存，后面会使用该class来反序列化生成响应对象，不移除会造成classLoader不一样，classCastException
        ReflectUtils.modifyField("NAME_CLASS_CACHE", org.apache.dubbo.common.utils.ReflectUtils.class,new ConcurrentHashMap<String, Class<?>>());
        ReflectUtils.modifyField("DESC_CLASS_CACHE", org.apache.dubbo.common.utils.ReflectUtils.class,new ConcurrentHashMap<String, Class<?>>());
        ReflectUtils.modifyField("Signature_METHODS_CACHE", org.apache.dubbo.common.utils.ReflectUtils.class,new ConcurrentHashMap<String, Method>());
    }
}
