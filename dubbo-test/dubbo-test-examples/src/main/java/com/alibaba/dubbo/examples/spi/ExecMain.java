package com.alibaba.dubbo.examples.spi;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;

/**
 * Created with IntelliJ IDEA.
 *
 * @author: zhubo
 * @description:
 * @time: 2018年05月16日
 * @modifytime:
 */
public class ExecMain {
    public static void main(String[] args) {
        /*AInterface adaptiveExtension = ExtensionLoader.getExtensionLoader(AInterface.class).getExtension("");
        adaptiveExtension.sayHello("zhangsan",URL.valueOf("http://www.baidu.com"));*/

        /*ExtensionLoader<BInterface> extensionLoader = ExtensionLoader.getExtensionLoader(BInterface.class);
        BInterface adaptiveExtension = extensionLoader.getAdaptiveExtension();
        URL url = URL.valueOf("http://www.baidu.com?b.interface=bdemo1");
        adaptiveExtension.sayHi("JJ",url);*/

        BInterface bwrapper = ExtensionLoader.getExtensionLoader(BInterface.class).getDefaultExtension();
        bwrapper.sayHi("JJ",URL.valueOf("http://www.baidu.com?b.interface=bdemo1"));

        BInterface bwrapper2 = ExtensionLoader.getExtensionLoader(BInterface.class).getAdaptiveExtension();
        bwrapper2.sayHi("JJ",URL.valueOf("http://www.baidu.com?b.interface=bdemo1"));

        /*AInterface adaptiveExtension = ExtensionLoader.getExtensionLoader(AInterface.class).getDefaultExtension();
        adaptiveExtension.sayHello("zhangsan",URL.valueOf("http://www.baidu.com"));*/

    }
}