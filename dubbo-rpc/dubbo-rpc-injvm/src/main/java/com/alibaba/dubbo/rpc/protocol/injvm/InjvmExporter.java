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
package com.alibaba.dubbo.rpc.protocol.injvm;

import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.protocol.AbstractExporter;

import java.util.Map;

/**
 * InjvmExporter
 *
 * @author william.liangf
 */
class InjvmExporter<T> extends AbstractExporter<T> {

    private final String key;

    private final Map<String, Exporter<?>> exporterMap;

    /**
     *
     * @param invoker
     * @param key
     * @param exporterMap {@link com.alibaba.dubbo.rpc.protocol.AbstractProtocol.exporterMap}
     *                     exporterMap 属性，Exporter 集合。在上文 InjvmProtocol#export(invoker) 方法中，我们可以看到，该属性就是 AbstractProtocol.exporterMap 属性。
     *
     *                     构造方法，发起暴露，将自己添加到 exporterMap 中。
     *                     #unexport() 方法，取消暴露，将自己移除出 exporterMap 中。
     */
    InjvmExporter(Invoker<T> invoker, String key, Map<String, Exporter<?>> exporterMap) {
        super(invoker);
        this.key = key;
        this.exporterMap = exporterMap;
        exporterMap.put(key, this);
    }

    public void unexport() {
        super.unexport();
        exporterMap.remove(key);
    }

}