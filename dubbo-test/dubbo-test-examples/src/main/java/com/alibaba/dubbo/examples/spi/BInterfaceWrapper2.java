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
public class BInterfaceWrapper2 implements BInterface{

    BInterface bInterface;

    public BInterfaceWrapper2(BInterface bInterface) {
        this.bInterface = bInterface;
    }

    @Override
    public void sayHi(String name, URL url) {
        System.out.println("begin wrapper2 hi: " + name);
        bInterface.sayHi(name,url);
        System.out.println("end   wrapper2 hi: " + name);
    }
}
