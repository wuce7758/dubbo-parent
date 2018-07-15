package com.alibaba.dubbo;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Created with IntelliJ IDEA.
 *
 * @author: zhubo
 * @description:
 * @time: 2018年06月03日
 * @modifytime:
 */
public class ConsistencyHash {

    private TreeMap<Long,Object> ketamaNodes = null;
    //真实服务器节点信息
    private List<Object> shards = new ArrayList();
    //设置虚拟节点数目
    private int VIRTUAL_NUM = 5000;
    //记录node对应的times,统计用
    private static Map<Object,Integer> resmap = new HashMap<Object,Integer>();
    /**
     * 初始化
     */
    public List<Object> init(int n) {
        for (int i=0;i<n;i++)
        {
            shards.add("node"+i);
            resmap.put("node"+i, 0);
        }
        return shards;

    }

    public void KetamaNodeLocator(List<Object> nodes, int nodeCopies) {
        ketamaNodes = new TreeMap<Long,Object>();
        //对所有节点，生成nCopies个虚拟结点
        for (Object node : nodes) {
            //每四个虚拟结点为一组
            for (int i = 0; i < nodeCopies / 4; i++)
            {
                //，Md5是一个16字节长度的数组将16字节的数组每四个字节一组， 分别对应一个虚拟结点
                byte[] digest = computeMd5(node.toString() + i);
                //对于每四个字节，组成一个long值数值，做为这个虚拟节点的在环中的惟一key
                for(int j=0; j<4; j++) {
                    long m = hash(digest,j); // 每4个字节组成唯一long整形，组成环上唯一节点的值
                    ketamaNodes.put(m, node);
                }
            }
        }
    }

    /**
     * 根据key的hash值取得服务器节点信息
     * @param hash
     * @return
     */
    public Object getShardInfo(long hash) {
        Long key = hash;
        //如果找到这个节点，直接取节点，返回
        if(!ketamaNodes.containsKey(key))
        {
            //得到大于当前key的那个子Map，然后从中取出第一个key，就是大于且离它最近的那个key
            SortedMap<Long, Object> tailMap=ketamaNodes.tailMap(key);
            if(tailMap.isEmpty()) {
                key = ketamaNodes.firstKey();
            } else {
                key = tailMap.firstKey();
            }
        }
        return ketamaNodes.get(key);
    }

    /**
     * 打印圆环节点数据
     */
    public void printMap() {
        System.out.println(ketamaNodes.size()+":" );
    }

    /**
     * 根据2^32把节点分布到圆环上面。
     * @param digest
     * @param nTime
     * @return
     */
    public long hash(byte[] digest, int nTime) {
        long rv = ((long) (digest[3+nTime*4] & 0xFF) << 24)
                | ((long) (digest[2+nTime*4] & 0xFF) << 16)
                | ((long) (digest[1+nTime*4] & 0xFF) << 8)
                | (digest[0+nTime*4] & 0xFF);

        return rv & 0xffffffffL; /* Truncate to 32-bits */
    }

    /**
     * Get the md5 of the given key.
     * 计算MD5值
     */
    public byte[] computeMd5(String k) {
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not supported", e);
        }
        md5.reset();
        byte[] keyBytes = null;
        try {
            keyBytes = k.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Unknown string :" + k, e);
        }

        md5.update(keyBytes);
        return md5.digest();
    }

    public static void main(String[] args) {
        Random ran = new Random();
        ConsistencyHash hash = new ConsistencyHash();

        int node = 12;
        int count =1000000;
        List<Object> nodes= hash.init(node);
        hash.KetamaNodeLocator(nodes,hash.VIRTUAL_NUM);
        System.out.println("node"+node+" count:"+count+", Normal percent : " + (float) 100 /node + "%");
        System.out.println("**********detail*************");

        for(int i=0; i<count; i++) {

            long tmp = hash.hash(hash.computeMd5(String.valueOf(i)),ran.nextInt(4));
            Object o =hash.getShardInfo(tmp);
            int sum =resmap.get(o);
            sum++;
            resmap.put(o, sum);
        }
        for(Object entry:resmap.keySet())
        {
            System.out.println("name:"+entry+"-times:"+resmap.get(entry)+"-percent:"+((resmap.get(entry)*100/count))+"%");
        }
    }


}
