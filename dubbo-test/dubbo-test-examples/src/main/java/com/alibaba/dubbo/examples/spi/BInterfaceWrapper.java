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
public class BInterfaceWrapper implements BInterface{

    BInterface bInterface;

    public BInterfaceWrapper(BInterface bInterface) {
        this.bInterface = bInterface;
    }

    @Override
    public void sayHi(String name, URL url) {
        System.out.println("begin wrapper1 hi: " + name);
        bInterface.sayHi(name,url);
        System.out.println("end   wrapper1 hi: " + name);
    }
}
