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
public class BInterfaceImpl implements BInterface{

    @Override
    public void sayHi(String name, URL url) {
        System.out.println("B1 hi: " + name);
    }
}
