package com.hmdp.utils;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    //锁的前缀
    private static final  String KEY_PREFIX = "lock:";
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
        long threadId = Thread.currentThread().getId();
        // 获取锁，使用SETNX方法进行加锁，同时设置过期时间，防止死锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId + "", timeoutSec, TimeUnit.SECONDS);
        // 自动拆箱可能会出现null，这样稳妥
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock() {
        // 通过del来删除锁
        stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}
