package chao.java.tools.servicepool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * @author qinchao
 * @since 2019/5/3
 */
public class DefaultServiceController implements ServiceController {

    private Map<Integer, ServiceProxy> serviceCache = new HashMap<>();

    private DependencyManager dependencyManager;

    private NoOpInstanceFactory noOpFactory;

    private List<ServiceFactories> factoriesList = new ArrayList<>(1);


    public DefaultServiceController() {
        noOpFactory = new NoOpInstanceFactory();
        ServiceLoader<DependencyManager> dependencyManagers = ServiceLoader.load(DependencyManager.class);
        for (DependencyManager dependencyManager: dependencyManagers) {
            this.dependencyManager = dependencyManager;
        }
        if (dependencyManager == null) {
            this.dependencyManager = new DefaultDependencyManager();
        }
    }

    @Override
    public void addService(Class<? extends IService> serviceClass) {

        ServiceProxy proxy = serviceCache.get(serviceClass.hashCode());
        if (proxy == null) {
            proxy = new ServiceProxy(serviceClass);
        }

        cacheService(proxy, serviceClass);

        cacheSubClasses(serviceClass, proxy);

        if (IInitService.class.isAssignableFrom(serviceClass)) {
            IInitService initService = (IInitService) proxy.getService();
//            dependencyManager.addService(initService);
        }
    }

    private void cacheService(ServiceProxy proxy, Class<?> serviceClass) {
        if (serviceClass == Object.class) {
            return;
        }
        ServiceProxy oldProxy = serviceCache.get(serviceClass.hashCode());
        //1. service还不存在
        //2. 申请的serviceClass和缓存key一致时，属于第一优先级
        //3. service已存在，但是当前的service优先级更高
        if (oldProxy == null || (!oldProxy.getServiceClass().equals(serviceClass)
            && proxy.priority() > oldProxy.priority())) {
            serviceCache.put(serviceClass.hashCode(), proxy);
        }
    }

    private void cacheSubClasses(Class<?> clazz, ServiceProxy serviceProxy) {
        if (clazz == Object.class) {
            return;
        }
        for (Class<?> subInterface: clazz.getInterfaces()) {
            if (IService.class.equals(subInterface)) {
                continue;
            }
            if (IInitService.class.equals(subInterface)) {
                continue;
            }
            cacheService(serviceProxy, subInterface);
        }
        Class superClass = clazz;
        while (superClass != Object.class) {
            cacheService(serviceProxy, superClass);
            superClass = superClass.getSuperclass();
        }
    }

    private ServiceProxy getService(Class<?> serviceClass) {
        ServiceProxy proxy = serviceCache.get(serviceClass.hashCode());
        if (proxy == null) {
            for (ServiceFactories factories: factoriesList) {
                String name = serviceClass.getName();
                int last = name.lastIndexOf('.');
                if (last == -1) {
                    continue;
                }
                String pkgName = name.substring(0, last);
                IServiceFactory factory = factories.getServiceFactory(pkgName);
                if (factory == null) {
                    continue;
                }
                ServiceProxy service = factory.createServiceProxy(serviceClass);
                if (service != null) {
                    cacheService(service, service.getServiceClass());
                    addService(service.getServiceClass());
                    proxy = serviceCache.get(service.getServiceClass().hashCode());

                    break;
                }
            }
        }
        return proxy;
    }


    public void addServices(Iterable<Class<? extends IService>> services) {
        for (Class<? extends IService> serviceClass: services) {
            addService(serviceClass);
        }
    }

    @Override
    public <T> T getServiceByClass(Class clazz, Class<T> t) {
        ServiceProxy serviceProxy = getService(clazz);
        if (serviceProxy != null) {
            return t.cast(serviceProxy.getService());
        }
        return noOpFactory.newInstance(t);
    }

    @Override
    public void loadFinished() {
        dependencyManager.servicesInit();
    }

    public <T extends IService> T getServiceByClass(Class serviceClass, Class<T> tClass, T defaultService) {
        ServiceProxy serviceProxy = getService(serviceClass);
        if (serviceProxy != null) {
            return tClass.cast(serviceProxy.getService());
        }
        return defaultService;
    }

    public void addFactories(ServiceFactories factories) {
        factoriesList.add(factories);
    }
}
