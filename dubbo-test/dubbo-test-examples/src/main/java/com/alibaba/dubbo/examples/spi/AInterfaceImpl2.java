package com.alibaba.dubbo.examples.spi;

import com.alibaba.dubbo.common.URL;

/**
 * Created with IntelliJ IDEA.
 *
 * @author: zhubo
 * @description:
 * @time: 2018年05月16日
 * @modifytime:
 */
public class AInterfaceImpl2 implements AInterface {

    @Override
    public void sayHello(String name, URL rul) {
        System.out.println("A2 hello: " + name);
    }


}
