/*
 * Copyright 1999-2011 Alibaba Group.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.config;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.bytecode.Wrapper;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.ClassHelper;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.dubbo.config.support.Parameter;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.cluster.ConfiguratorFactory;
import com.alibaba.dubbo.rpc.service.GenericService;
import com.alibaba.dubbo.rpc.support.ProtocolUtils;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ServiceConfig
 *
 * @author william.liangf
 * @export
 */
public class ServiceConfig<T> extends AbstractServiceConfig {

    private static final long serialVersionUID = 3033787999037024738L;

    private static final Protocol protocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    private static final ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    private static final Map<String, Integer> RANDOM_PORT_MAP = new HashMap<String, Integer>();

    private static final ScheduledExecutorService delayExportExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DubboServiceDelayExporter", true));
    private final List<URL> urls = new ArrayList<URL>();
    private final List<Exporter<?>> exporters = new ArrayList<Exporter<?>>();
    // 接口类型
    private String interfaceName;
    private Class<?> interfaceClass;
    // 接口实现类引用
    private T ref;
    // 服务名称
    private String path;
    // 方法配置
    private List<MethodConfig> methods;
    private ProviderConfig provider;
    private transient volatile boolean exported;

    private transient volatile boolean unexported;

    private volatile String generic;

    public ServiceConfig() {
    }

    public ServiceConfig(Service service) {
        appendAnnotation(Service.class, service);
    }

    @Deprecated
    private static final List<ProtocolConfig> convertProviderToProtocol(List<ProviderConfig> providers) {
        if (providers == null || providers.size() == 0) {
            return null;
        }
        List<ProtocolConfig> protocols = new ArrayList<ProtocolConfig>(providers.size());
        for (ProviderConfig provider : providers) {
            protocols.add(convertProviderToProtocol(provider));
        }
        return protocols;
    }

    @Deprecated
    private static final List<ProviderConfig> convertProtocolToProvider(List<ProtocolConfig> protocols) {
        if (protocols == null || protocols.size() == 0) {
            return null;
        }
        List<ProviderConfig> providers = new ArrayList<ProviderConfig>(protocols.size());
        for (ProtocolConfig provider : protocols) {
            providers.add(convertProtocolToProvider(provider));
        }
        return providers;
    }

    @Deprecated
    private static final ProtocolConfig convertProviderToProtocol(ProviderConfig provider) {
        ProtocolConfig protocol = new ProtocolConfig();
        protocol.setName(provider.getProtocol().getName());
        protocol.setServer(provider.getServer());
        protocol.setClient(provider.getClient());
        protocol.setCodec(provider.getCodec());
        protocol.setHost(provider.getHost());
        protocol.setPort(provider.getPort());
        protocol.setPath(provider.getPath());
        protocol.setPayload(provider.getPayload());
        protocol.setThreads(provider.getThreads());
        protocol.setParameters(provider.getParameters());
        return protocol;
    }

    @Deprecated
    private static final ProviderConfig convertProtocolToProvider(ProtocolConfig protocol) {
        ProviderConfig provider = new ProviderConfig();
        provider.setProtocol(protocol);
        provider.setServer(protocol.getServer());
        provider.setClient(protocol.getClient());
        provider.setCodec(protocol.getCodec());
        provider.setHost(protocol.getHost());
        provider.setPort(protocol.getPort());
        provider.setPath(protocol.getPath());
        provider.setPayload(protocol.getPayload());
        provider.setThreads(protocol.getThreads());
        provider.setParameters(protocol.getParameters());
        return provider;
    }

    private static Integer getRandomPort(String protocol) {
        protocol = protocol.toLowerCase();
        if (RANDOM_PORT_MAP.containsKey(protocol)) {
            return RANDOM_PORT_MAP.get(protocol);
        }
        return Integer.MIN_VALUE;
    }

    private static void putRandomPort(String protocol, Integer port) {
        protocol = protocol.toLowerCase();
        if (!RANDOM_PORT_MAP.containsKey(protocol)) {
            RANDOM_PORT_MAP.put(protocol, port);
        }
    }

    public URL toUrl() {
        return urls == null || urls.size() == 0 ? null : urls.iterator().next();
    }

    public List<URL> toUrls() {
        return urls;
    }

    @Parameter(excluded = true)
    public boolean isExported() {
        return exported;
    }

    @Parameter(excluded = true)
    public boolean isUnexported() {
        return unexported;
    }

    public synchronized void export() {
        if (provider != null) {
            if (export == null) {
                export = provider.getExport();
            }
            if (delay == null) {
                delay = provider.getDelay();
            }
        }
        // 如果该服务已暴露，则直接返回。
        if (export != null && !export) {
            return;
        }

        // 如果启用了delay机制，如果delay大于0，表示延迟多少毫秒后暴露服务，使用ScheduledExecutorService延迟调度，最终调用doExport方法。
        if (delay != null && delay > 0) {
            delayExportExecutor.schedule(new Runnable() {
                public void run() {
                    doExport();
                }
            }, delay, TimeUnit.MILLISECONDS);
        } else {
            doExport();
        }
    }

    protected synchronized void doExport() {
        if (unexported) {
            throw new IllegalStateException("Already unexported!");
        }
        if (exported) {
            return;
        }
        exported = true;
        if (interfaceName == null || interfaceName.length() == 0) {
            throw new IllegalStateException("<dubbo:service interface=\"\" /> interface not allow null!");
        }
        // 设置 provider = new ProviderConfig();  并为其中的属性利用System.getProperty方式为其中属性复制
        // 如果dubbo:servce标签也就是ServiceBean的provider属性为空，调用appendProperties方法，填充默认属性，其具体加载顺序：
        checkDefault();
        if (provider != null) {
            if (application == null) {
                application = provider.getApplication();
            }
            if (module == null) {
                module = provider.getModule();
            }
            if (registries == null) {
                registries = provider.getRegistries();
            }
            if (monitor == null) {
                monitor = provider.getMonitor();
            }
            if (protocols == null) {
                protocols = provider.getProtocols();
            }
        }
        if (module != null) {
            if (registries == null) {
                registries = module.getRegistries();
            }
            if (monitor == null) {
                monitor = module.getMonitor();
            }
        }
        if (application != null) {
            if (registries == null) {
                registries = application.getRegistries();
            }
            if (monitor == null) {
                monitor = application.getMonitor();
            }
        }
        if (ref instanceof GenericService) {
            interfaceClass = GenericService.class;
            if (StringUtils.isEmpty(generic)) {
                generic = Boolean.TRUE.toString();
            }
        } else {
            try {
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // 校验接口类型
            checkInterfaceAndMethods(interfaceClass, methods);
            // 校验ref不能为空 并且为接口类型的实现类
            checkRef();
            // 不为通用的调用类型
            generic = Boolean.FALSE.toString();
        }
        if (local != null) {
            if ("true".equals(local)) {
                local = interfaceName + "Local";
            }
            Class<?> localClass;
            try {
                localClass = ClassHelper.forNameWithThreadContextClassLoader(local);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implemention class " + localClass.getName() + " not implement interface " + interfaceName);
            }
        }
        //Step3：dubbo:service local机制，已经废弃，被stub属性所替换。
        // Step4：处理本地存根Stub，
        if (stub != null) {
            if ("true".equals(stub)) {
                stub = interfaceName + "Stub";
            }
            Class<?> stubClass;
            try {
                stubClass = ClassHelper.forNameWithThreadContextClassLoader(stub);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(stubClass)) {
                throw new IllegalStateException("The stub implemention class " + stubClass.getName() + " not implement interface " + interfaceName);
            }
        }
        // 校验ServiceBean的application、registry、protocol是否为空，并从系统属性（优先）、资源文件中填充其属性。
        /**
         * 系统属性、资源文件属性的配置如下：
         * application dubbo.application.属性名，例如 dubbo.application.name
           registry dubbo.registry.属性名，例如 dubbo.registry.address
           protocol dubbo.protocol.属性名，例如 dubbo.protocol.port
           service dubbo.service.属性名，例如 dubbo.service.stub
         */
        checkApplication();
        checkRegistry();
        checkProtocol();
        appendProperties(this);
        // 校验stub、mock类的合理性，是否是interface的实现类。
        checkStubAndMock(interfaceClass);
        if (path == null || path.length() == 0) {
            path = interfaceName;
        }
        doExportUrls();
    }

    private void checkRef() {
        // 检查引用不为空，并且引用必需实现接口
        if (ref == null) {
            throw new IllegalStateException("ref not allow null!");
        }
        if (!interfaceClass.isInstance(ref)) {
            throw new IllegalStateException("The class "
                    + ref.getClass().getName() + " unimplemented interface "
                    + interfaceClass + "!");
        }
    }

    public synchronized void unexport() {
        if (!exported) {
            return;
        }
        if (unexported) {
            return;
        }
        if (exporters != null && exporters.size() > 0) {
            for (Exporter<?> exporter : exporters) {
                try {
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn("unexpected err when unexport" + exporter, t);
                }
            }
            exporters.clear();
        }
        unexported = true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doExportUrls() {
        //该方法根据配置文件装配成一个URL的list
        // 参数的意思：true，代表服务提供者，false：代表服务消费者
        List<URL> registryURLs = loadRegistries(true);   // 加载注册中心信息 registry://172.16.11.118:2181/com.alibaba.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.0&pid=11380&registry=zookeeper&timestamp=1527860175398
        for (ProtocolConfig protocolConfig : protocols) { // <dubbo:protocol name="dubbo" port="20880" id="dubbo" />  协议及暴露地址
            //根据每一个协议配置来分别暴露服务
            doExportUrlsFor1Protocol(protocolConfig, registryURLs);
        }
    }

    // protoConfig : <dubbo:protocol name="dubbo" port="20880" id="dubbo" />
    // registryURLs : registry://172.16.11.118:2181/com.alibaba.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.0&pid=8468&registry=zookeeper&timestamp=1527860947510
    private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs) {
        String name = protocolConfig.getName();
        if (name == null || name.length() == 0) {
            name = "dubbo";
        }
        // 服务IP地址(多网卡时使用)
        String host = protocolConfig.getHost();
        if (provider != null && (host == null || host.length() == 0)) {
            host = provider.getHost();
        }
        boolean anyhost = false;
        if (NetUtils.isInvalidLocalHost(host)) {
            anyhost = true;
            try {
                host = InetAddress.getLocalHost().getHostAddress();
            } catch (UnknownHostException e) {
                logger.warn(e.getMessage(), e);
            }
            if (NetUtils.isInvalidLocalHost(host)) {
                if (registryURLs != null && registryURLs.size() > 0) {
                    for (URL registryURL : registryURLs) {
                        try {
                            Socket socket = new Socket();
                            try {
                                SocketAddress addr = new InetSocketAddress(registryURL.getHost(), registryURL.getPort());
                                socket.connect(addr, 1000);
                                host = socket.getLocalAddress().getHostAddress();
                                break;
                            } finally {
                                try {
                                    socket.close();
                                } catch (Throwable e) {
                                }
                            }
                        } catch (Exception e) {
                            logger.warn(e.getMessage(), e);
                        }
                    }
                }
                if (NetUtils.isInvalidLocalHost(host)) {
                    host = NetUtils.getLocalHost();
                }
            }
        }

        Integer port = protocolConfig.getPort();
        if (provider != null && (port == null || port == 0)) {
            port = provider.getPort();
        }
        final int defaultPort = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(name).getDefaultPort();
        if (port == null || port == 0) {
            port = defaultPort;
        }
        if (port == null || port <= 0) {
            port = getRandomPort(name);
            if (port == null || port < 0) {
                port = NetUtils.getAvailablePort(defaultPort);
                putRandomPort(name, port);
            }
            logger.warn("Use random available port(" + port + ") for protocol " + name);
        }


        Map<String, String> map = new HashMap<String, String>();
        if (anyhost) {
            map.put(Constants.ANYHOST_KEY, "true");
        }
        map.put(Constants.SIDE_KEY, Constants.PROVIDER_SIDE);
        map.put(Constants.DUBBO_VERSION_KEY, Version.getVersion());
        map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
        if (ConfigUtils.getPid() > 0) {
            map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
        }
        // 用Map存储该协议的所有配置参数，包括协议名称、dubbo版本、当前系统时间戳、进程ID、
        // application配置、module配置、默认服务提供者参数(ProviderConfig)、协议配置、服务提供Dubbo:service的属性。
        appendParameters(map, application);//<dubbo:application name="demo-provider" id="demo-provider" />
        appendParameters(map, module);
        appendParameters(map, provider, Constants.DEFAULT_KEY);  //  <dubbo:provider />
        appendParameters(map, protocolConfig);   // <dubbo:protocol name="dubbo" port="20880" id="dubbo" />
        appendParameters(map, this);
        if (methods != null && methods.size() > 0) {
            for (MethodConfig method : methods) {
                appendParameters(map, method, method.getName());
                String retryKey = method.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        map.put(method.getName() + ".retries", "0");
                    }
                }
                List<ArgumentConfig> arguments = method.getArguments();
                if (arguments != null && arguments.size() > 0) {
                    for (ArgumentConfig argument : arguments) {
                        //类型自动转换.
                        if (argument.getType() != null && argument.getType().length() > 0) {
                            Method[] methods = interfaceClass.getMethods();
                            //遍历所有方法
                            if (methods != null && methods.length > 0) {
                                for (int i = 0; i < methods.length; i++) {
                                    String methodName = methods[i].getName();
                                    //匹配方法名称，获取方法签名.
                                    if (methodName.equals(method.getName())) {
                                        Class<?>[] argtypes = methods[i].getParameterTypes();
                                        //一个方法中单个callback
                                        if (argument.getIndex() != -1) {
                                            if (argtypes[argument.getIndex()].getName().equals(argument.getType())) {
                                                appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                                            } else {
                                                throw new IllegalArgumentException("argument config error : the index attribute and type attirbute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                            }
                                        } else {
                                            //一个方法中多个callback
                                            for (int j = 0; j < argtypes.length; j++) {
                                                Class<?> argclazz = argtypes[j];
                                                if (argclazz.getName().equals(argument.getType())) {
                                                    appendParameters(map, argument, method.getName() + "." + j);
                                                    if (argument.getIndex() != -1 && argument.getIndex() != j) {
                                                        throw new IllegalArgumentException("argument config error : the index attribute and type attirbute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (argument.getIndex() != -1) {
                            appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                        } else {
                            throw new IllegalArgumentException("argument config must set index or type attribute.eg: <dubbo:argument index='0' .../> or <dubbo:argument type=xxx .../>");
                        }

                    }
                }
            } // end of methods for
        }
        //添加methods键值对，存放dubbo:service的所有方法名，多个方法名用,隔开，如果是泛化实现，填充genric=true,methods为”*”；
        if (ProtocolUtils.isGeneric(generic)) {
            map.put("generic", generic);
            map.put("methods", Constants.ANY_VALUE);
        } else {
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                map.put("revision", revision);
            }

            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            if (methods.length == 0) {
                logger.warn("NO method found in service interface " + interfaceClass.getName());
                map.put("methods", Constants.ANY_VALUE);
            } else {
                map.put("methods", StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
            }
        }
        // 根据是否开启令牌机制，如果开启，设置token键，值为静态值或uuid
        if (!ConfigUtils.isEmpty(token)) {
            if (ConfigUtils.isDefault(token)) {
                map.put("token", UUID.randomUUID().toString());
            } else {
                map.put("token", token);
            }
        }
        // 如果协议为本地协议(injvm)，则设置protocolConfig#register属性为false，表示不向注册中心注册服务，
        // 在map中存储键为notify,值为false,表示当注册中心监听到服务提供者发送变化（服务提供者增加、服务提供者减少等事件时不通知。
        if ("injvm".equals(protocolConfig.getName())) {
            protocolConfig.setRegister(false);
            map.put("notify", "false");
        }
        // 设置协议的contextPath,如果未配置，默认为interfacename
        String contextPath = protocolConfig.getContextpath();
        if ((contextPath == null || contextPath.length() == 0) && provider != null) {
            contextPath = provider.getContextpath();
        }
        // 根据协议名称、协议host、协议端口、contextPath、相关配置属性（application、module、provider、protocolConfig、service及其子标签）构建服务提供者URI
        URL url = new URL(name, host, port, (contextPath == null || contextPath.length() == 0 ? "" : contextPath + "/") + path, map);
        // dubbo://192.168.20.157:20880/com.alibaba.dubbo.demo.DemoService
        // ?anyhost=true&application=demo-provider&dubbo=2.0.0&generic=false&interface=com.alibaba.dubbo.demo.DemoService
        // &methods=sayHello&pid=2112&side=provider&timestamp=1527861353022
        if (ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                .hasExtension(url.getProtocol())) {
            url = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                    .getExtension(url.getProtocol()).getConfigurator(url).configure(url);
        }

        String scope = url.getParameter(Constants.SCOPE_KEY);
        //获取dubbo:service标签的scope属性，其可选值为none(不暴露)、local(本地)、remote(远程)，如果配置为none，则不暴露。默认为local。
        if (!Constants.SCOPE_NONE.toString().equalsIgnoreCase(scope)) {

            //配置不是remote的情况下做本地暴露 (配置为remote，则表示只暴露远程服务)
            if (!Constants.SCOPE_REMOTE.toString().equalsIgnoreCase(scope)) {
                exportLocal(url); // dubbo://192.168.20.157:20880/com.alibaba.dubbo.demo.DemoService?anyhost=true&application=demo-provider&dubbo=2.0.0&generic=false&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello&pid=13504&side=provider&timestamp=1527861656943
            }
            //如果配置不是local则暴露为远程服务.(配置为local，则表示只暴露本地服务)
            if (!Constants.SCOPE_LOCAL.toString().equalsIgnoreCase(scope)) {
                if (logger.isInfoEnabled()) {
                    logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                }
                if (registryURLs != null && registryURLs.size() > 0
                        && url.getParameter("register", true)) {
                    // 循环祖册中心 URL 数组 registryURLs
                    for (URL registryURL : registryURLs) {
                        // "dynamic" ：服务是否动态注册，如果设为false，注册后将显示后disable状态，需人工启用，并且服务提供者停止时，也不会自动取消册，需人工禁用。
                        url = url.addParameterIfAbsent("dynamic", registryURL.getParameter("dynamic"));
                        // 获得监控中心 URL
                        URL monitorUrl = loadMonitor(registryURL);
                        if (monitorUrl != null) {
                            // 将监控中心的 URL 作为 "monitor" 参数添加到服务提供者的 URL 中，并且需要编码。通过这样的方式，服务提供者的 URL 中，包含了监控中心的配置。
                            url = url.addParameterAndEncoded(Constants.MONITOR_KEY, monitorUrl.toFullString());
                        }
                        if (logger.isInfoEnabled()) {
                            logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to registry " + registryURL);
                        }
                        // 使用 ProxyFactory 创建 Invoker 对象
                        // 调用 URL#addParameterAndEncoded(key, value) 方法，将服务体用这的 URL 作为 "export" 参数添加到注册中心的 URL 中。通过这样的方式，注册中心的 URL 中，包含了服务提供者的配置。
                        // 创建 Invoker 对象。该 Invoker 对象，执行 #invoke(invocation) 方法时，内部会调用 Service 对象( ref )对应的调用方法。
                        Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, registryURL.addParameterAndEncoded(Constants.EXPORT_KEY, url.toFullString()));
                        // 使用 Protocol 暴露 Invoker 对象
                        /**
                         * Protocol$Adaptive => ProtocolFilterWrapper => ProtocolListenerWrapper => RegistryProtocol
                         * =>
                         * Protocol$Adaptive => ProtocolFilterWrapper => ProtocolListenerWrapper => DubboProtocol
                         */
                        Exporter<?> exporter = protocol.export(invoker);
                        // 添加到 `exporters`
                        exporters.add(exporter);
                    }
                } else {// 用于被服务消费者直连服务提供者，参见文档 https://dubbo.gitbooks.io/dubbo-user-book/demos/explicit-target.html 。主要用于开发测试环境使用。
                    // 使用 ProxyFactory 创建 Invoker 对象
                    Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, url);
                    // 使用 Protocol 暴露 Invoker 对象
                    Exporter<?> exporter = protocol.export(invoker);
                    // 添加到 `exporters`
                    exporters.add(exporter);
                }
            }
        }
        this.urls.add(url);
    }

    // 上面远程暴露和本地直连 代码相差不大 差别在于，当配置注册中心为 "N/A" 时，表示即使远程暴露服务，也不向注册中心注册。这种方式用于被服务消费者直连服务提供者

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void exportLocal(URL url) {
        if (!Constants.LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {

            // 基于原有 url ，创建新的服务的本地 Dubbo URL 对象，并设置属性 protocol=injvm host=127.0.0.1 port=0 。因为 url 在后面远程暴露服务会使用，所以要新创建。
            URL local = URL.valueOf(url.toFullString())
                    .setProtocol(Constants.LOCAL_PROTOCOL)
                    .setHost(NetUtils.LOCALHOST)
                    .setPort(0);
            // 使用 ProxyFactory 创建 Invoker 对象
            // 使用 Protocol 暴露 Invoker 对象
            // 调用 ProxyFactory#getInvoker(proxy, type, url) 方法，创建 Invoker 对象。该 Invoker 对象，执行 #invoke(invocation) 方法时，内部会调用 Service 对象( ref )对应的调用方法。
            //  Dubbo SPI 自适应的特性的好处就出来了，可以自动根据 URL 参数，获得对应的拓展实现。例如，invoker 传入后，根据 invoker.url 自动获得对应 Protocol 拓展实现为 InjvmProtocol 。
            //  Protocol 有两个 Wrapper 拓展实现类： ProtocolFilterWrapper、ProtocolListenerWrapper 。所以，#export(...) 方法的调用顺序是：
            // Protocol$Adaptive => ProtocolFilterWrapper => ProtocolListenerWrapper => InjvmProtocol 。
            Exporter<?> exporter = protocol.export(
                    proxyFactory.getInvoker(ref, (Class) interfaceClass, local));
            // 添加到 `exporters`
            exporters.add(exporter);
            logger.info("Export dubbo service " + interfaceClass.getName() + " to local registry");
        }
    }

    private void checkDefault() {
        // 如果dubbo:servce标签也就是ServiceBean的provider属性为空，调用appendProperties方法，填充默认属性，其具体加载顺序：
        if (provider == null) {
            provider = new ProviderConfig();
        }
        //1）从系统属性加载对应参数值，参数键：dubbo.provider.属性名，System.getProperty。
        // 2）加载属性配置文件的值。属性配置文件，可通过系统属性：dubbo.properties.file，如果该值未配置，则默认取dubbo.properties属性配置文件。
        appendProperties(provider);
    }

    private void checkProtocol() {
        if ((protocols == null || protocols.size() == 0)
                && provider != null) {
            setProtocols(provider.getProtocols());
        }
        // 兼容旧版本
        if (protocols == null || protocols.size() == 0) {
            setProtocol(new ProtocolConfig());
        }
        for (ProtocolConfig protocolConfig : protocols) {
            if (StringUtils.isEmpty(protocolConfig.getName())) {
                protocolConfig.setName("dubbo");
            }
            appendProperties(protocolConfig);
        }
    }

    public Class<?> getInterfaceClass() {
        if (interfaceClass != null) {
            return interfaceClass;
        }
        if (ref instanceof GenericService) {
            return GenericService.class;
        }
        try {
            if (interfaceName != null && interfaceName.length() > 0) {
                this.interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            }
        } catch (ClassNotFoundException t) {
            throw new IllegalStateException(t.getMessage(), t);
        }
        return interfaceClass;
    }

    /**
     * @param interfaceClass
     * @see #setInterface(Class)
     * @deprecated
     */
    public void setInterfaceClass(Class<?> interfaceClass) {
        setInterface(interfaceClass);
    }

    public String getInterface() {
        return interfaceName;
    }

    public void setInterface(String interfaceName) {
        this.interfaceName = interfaceName;
        if (id == null || id.length() == 0) {
            id = interfaceName;
        }
    }

    public void setInterface(Class<?> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
        setInterface(interfaceClass == null ? (String) null : interfaceClass.getName());
    }

    public T getRef() {
        return ref;
    }

    public void setRef(T ref) {
        this.ref = ref;
    }

    @Parameter(excluded = true)
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        checkPathName("path", path);
        this.path = path;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    // ======== Deprecated ========

    @SuppressWarnings("unchecked")
    public void setMethods(List<? extends MethodConfig> methods) {
        this.methods = (List<MethodConfig>) methods;
    }

    public ProviderConfig getProvider() {
        return provider;
    }

    public void setProvider(ProviderConfig provider) {
        this.provider = provider;
    }

    public String getGeneric() {
        return generic;
    }

    public void setGeneric(String generic) {
        if (StringUtils.isEmpty(generic)) {
            return;
        }
        if (ProtocolUtils.isGeneric(generic)) {
            this.generic = generic;
        } else {
            throw new IllegalArgumentException("Unsupported generic type " + generic);
        }
    }

    public List<URL> getExportedUrls() {
        return urls;
    }

    /**
     * @deprecated Replace to getProtocols()
     */
    @Deprecated
    public List<ProviderConfig> getProviders() {
        return convertProtocolToProvider(protocols);
    }

    /**
     * @deprecated Replace to setProtocols()
     */
    @Deprecated
    public void setProviders(List<ProviderConfig> providers) {
        this.protocols = convertProviderToProtocol(providers);
    }
}
