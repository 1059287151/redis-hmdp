package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    //锁的前缀
    private static final String KEY_PREFIX = "lock:";
    //在获取锁的时候存入线程标识（用UUID标识，在一个JVM中，ThreadId一般不会重复，但是我们现在是集群模式，有多个JVM，多个JVM之间可能会出现ThreadId重复的情况）
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    //具体业务名称，将前缀和业务名拼接之后当作key
    private String name;
    //这里不是@autowired注入，采用的是构造器注入，在创建SimpleRedisLock是将RedisTemplate作为参数传入
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程表示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁，使用SETNX方法进行加锁，同时设置过期时间，防止死锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        // 自动拆箱可能会出现null，这样稳妥
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock() {
        // 获取当前线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁中的标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        // 判断是否一致
        if (threadId.equals(id)) {
            // 通过del来删除锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
